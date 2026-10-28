#include <jni.h>
#include <algorithm>
#include <atomic>
#include <cerrno>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <memory>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <unistd.h>
extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/audio_fifo.h>
#include <libavutil/display.h>
#include <libavutil/opt.h>
#include <libswresample/swresample.h>
}

namespace {
struct Session { std::atomic<bool> cancelled{false}; std::atomic<bool> busy{false}; };
std::mutex sessionsMutex;
std::unordered_map<int64_t, std::shared_ptr<Session>> sessions;
std::atomic<int64_t> nextSession{1};
struct Failure : std::runtime_error { using std::runtime_error::runtime_error; };
void check(int result, const char* operation) {
    if (result >= 0) return;
    if (result == AVERROR_EXIT) throw Failure("CANCELLED: operation cancelled");
    if (result == AVERROR(ENOSPC)) throw Failure("ENOSPC: device storage is full");
    if (result == AVERROR(EFBIG)) throw Failure("OUTPUT_TOO_LARGE: output exceeds format or 8 GiB size limit");
    char error[AV_ERROR_MAX_STRING_SIZE]; av_strerror(result, error, sizeof(error));
    throw Failure(std::string(operation) + ": " + error);
}
void require(bool condition, const char* message) { if (!condition) throw Failure(message); }
void cancelled(const std::shared_ptr<Session>& session) { if (session->cancelled.load()) check(AVERROR_EXIT, "cancelled"); }
void raise(JNIEnv* env, const std::exception& error) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/RuntimeException"), error.what());
}
std::string path(JNIEnv* env, jstring value) {
    require(value != nullptr, "Path is null");
    const char* chars = env->GetStringUTFChars(value, nullptr);
    require(chars != nullptr, "Cannot read path");
    std::string result(chars); env->ReleaseStringUTFChars(value, chars);
    require(!result.empty() && result[0] == '/', "Only absolute local file paths are supported");
    return result;
}
std::shared_ptr<Session> findSession(jlong id) {
    std::lock_guard<std::mutex> lock(sessionsMutex);
    const auto found = sessions.find(id);
    require(found != sessions.end(), "Invalid or released native session");
    return found->second;
}
struct Busy {
    std::shared_ptr<Session> session;
    explicit Busy(std::shared_ptr<Session> value) : session(std::move(value)) {
        bool expected = false;
        require(session->busy.compare_exchange_strong(expected, true), "Session is already running");
        if (session->cancelled.load()) { session->busy = false; check(AVERROR_EXIT, "cancelled"); }
    }
    ~Busy() { session->busy = false; }
};
int interrupt(void* opaque) { return static_cast<Session*>(opaque)->cancelled.load() ? 1 : 0; }
struct Input {
    AVFormatContext* context = nullptr;
    explicit Input(const std::string& file, Session* session) {
        context = avformat_alloc_context(); require(context, "Cannot allocate input context");
        context->interrupt_callback = {interrupt, session};
        context->max_analyze_duration = 5 * AV_TIME_BASE;
        context->probesize = 8 * 1024 * 1024;
        AVDictionary* options = nullptr;
        av_dict_set(&options, "protocol_whitelist", "file,pipe", 0);
        int result = avformat_open_input(&context, file.c_str(), nullptr, &options);
        av_dict_free(&options);
        if (result < 0) { avformat_close_input(&context); check(result, "Open input"); }
        result = avformat_find_stream_info(context, nullptr);
        if (result < 0) { avformat_close_input(&context); check(result, "Read media information"); }
    }
    ~Input() { avformat_close_input(&context); }
};
int64_t durationMs(AVFormatContext* context) {
    if (context->duration != AV_NOPTS_VALUE && context->duration > 0) return context->duration / 1000;
    int64_t result = 0;
    for (unsigned i = 0; i < context->nb_streams; ++i) {
        auto stream = context->streams[i];
        if (stream->duration != AV_NOPTS_VALUE && stream->duration > 0)
            result = std::max(result, av_rescale_q(stream->duration, stream->time_base, AVRational{1, 1000}));
    }
    return result;
}
struct OutputFile {
    FILE* file = nullptr;
    Session* session;
    int64_t limit;
    std::string path;
    bool committed = false;
    OutputFile(const std::string& name, Session* value, bool wav) : session(value), limit(wav ? 0xffffff00LL : 8LL * 1024 * 1024 * 1024), path(name) {
        int fd = open(path.c_str(), O_CREAT | O_EXCL | O_RDWR | O_CLOEXEC, 0600);
        if (fd < 0) check(AVERROR(errno), "Create output");
        file = fdopen(fd, "w+b");
        if (!file) { int code = errno; close(fd); unlink(path.c_str()); check(AVERROR(code), "Open output stream"); }
    }
    ~OutputFile() { if (file) fclose(file); if (!committed) unlink(path.c_str()); }
    static int write(void* opaque, const uint8_t* buffer, int size) {
        auto* self = static_cast<OutputFile*>(opaque);
        if (self->session->cancelled.load()) return AVERROR_EXIT;
        const auto position = ftello(self->file);
        if (position < 0) return AVERROR(errno);
        if (size < 0 || position > self->limit - size) return AVERROR(EFBIG);
        if (fwrite(buffer, 1, size, self->file) != static_cast<size_t>(size)) return AVERROR(errno ? errno : EIO);
        return size;
    }
    static int64_t seek(void* opaque, int64_t offset, int whence) {
        auto* self = static_cast<OutputFile*>(opaque);
        if (self->session->cancelled.load()) return AVERROR_EXIT;
        if (whence == AVSEEK_SIZE) {
            const auto current = ftello(self->file);
            if (fseeko(self->file, 0, SEEK_END)) return AVERROR(errno);
            const auto end = ftello(self->file);
            if (fseeko(self->file, current, SEEK_SET)) return AVERROR(errno);
            return end;
        }
        whence &= ~AVSEEK_FORCE;
        if (whence != SEEK_SET && whence != SEEK_CUR && whence != SEEK_END) return AVERROR(EINVAL);
        if (whence == SEEK_SET && (offset < 0 || offset > self->limit)) return AVERROR(EFBIG);
        if (fseeko(self->file, offset, whence)) return AVERROR(errno);
        const auto position = ftello(self->file);
        return position > self->limit ? AVERROR(EFBIG) : position;
    }
    void finish() {
        if (fflush(file)) check(AVERROR(errno ? errno : EIO), "Flush output");
        const int result = fclose(file); file = nullptr;
        if (result) check(AVERROR(errno ? errno : EIO), "Close output");
    }
};
struct Conversion {
    AVCodecContext* decoder = nullptr;
    AVCodecContext* encoder = nullptr;
    AVFormatContext* output = nullptr;
    AVIOContext* io = nullptr;
    SwrContext* resampler = nullptr;
    AVAudioFifo* fifo = nullptr;
    int inputRate = 0;
    AVSampleFormat inputFormat = AV_SAMPLE_FMT_NONE;
    AVChannelLayout inputLayout{};
    AVPacket* packet = av_packet_alloc();
    AVPacket* encoded = av_packet_alloc();
    AVFrame* frame = av_frame_alloc();
    std::unique_ptr<OutputFile> file;
    ~Conversion() {
        av_packet_free(&packet); av_packet_free(&encoded); av_frame_free(&frame);
        av_audio_fifo_free(fifo); swr_free(&resampler);
        av_channel_layout_uninit(&inputLayout);
        avcodec_free_context(&decoder); avcodec_free_context(&encoder);
        if (output) output->pb = nullptr;
        avformat_free_context(output);
        if (io) { av_freep(&io->buffer); avio_context_free(&io); }
    }
};
struct FrameDelete { void operator()(AVFrame* frame) const { av_frame_free(&frame); } };
using FramePtr = std::unique_ptr<AVFrame, FrameDelete>;
FramePtr audioFrame(AVCodecContext* encoder, int samples) {
    FramePtr result(av_frame_alloc()); require(result != nullptr, "Cannot allocate audio frame");
    result->format = encoder->sample_fmt; result->sample_rate = encoder->sample_rate; result->nb_samples = samples;
    check(av_channel_layout_copy(&result->ch_layout, &encoder->ch_layout), "Copy channel layout");
    check(av_frame_get_buffer(result.get(), 0), "Allocate audio samples"); return result;
}
int nearestRate(int requested, const AVCodec* codec) {
    const void* configurations = nullptr;
    check(avcodec_get_supported_config(nullptr, codec, AV_CODEC_CONFIG_SAMPLE_RATE, 0, &configurations, nullptr), "Read encoder sample rates");
    const int* rates = static_cast<const int*>(configurations);
    if (!rates) return requested;
    int best = rates[0];
    for (const int* rate = rates; *rate; ++rate)
        if (std::abs(*rate - requested) < std::abs(best - requested)) best = *rate;
    return best;
}
void convert(JNIEnv* env, const std::shared_ptr<Session>& session, const std::string& inputPath,
             const std::string& outputPath, const std::string& format, int rate, int channels, int bitrate, jobject callback) {
    require(inputPath != outputPath, "Input and output paths must differ");
    require(rate == 0 || (rate >= 8000 && rate <= 192000), "Unsupported sample rate");
    require(channels >= 0 && channels <= 8, "Unsupported channel count");
    require(bitrate >= 8000 && bitrate <= 512000, "Unsupported audio bitrate");
    require(callback != nullptr, "Progress callback is null");
    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID progressMethod = env->GetMethodID(callbackClass, "onProgress", "(F)V");
    env->DeleteLocalRef(callbackClass);
    require(progressMethod != nullptr && !env->ExceptionCheck(), "Progress callback must define onProgress(Float)");
    Input input(inputPath, session.get()); cancelled(session);
    int audio = av_find_best_stream(input.context, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
    check(audio, "Find audio stream");
    auto source = input.context->streams[audio];
    const AVCodec* decoder = avcodec_find_decoder(source->codecpar->codec_id);
    require(decoder, "Unsupported audio decoder");
    const char* encoderName = nullptr; const char* muxer = nullptr;
    if (format == "mp3") { encoderName = "libmp3lame"; muxer = "mp3"; }
    else if (format == "flac") { encoderName = "flac"; muxer = "flac"; }
    else if (format == "m4a") { encoderName = "aac"; muxer = "ipod"; }
    else if (format == "wav") { encoderName = "pcm_s16le"; muxer = "wav"; }
    else throw Failure("Unsupported output format");
    const AVCodec* encoder = avcodec_find_encoder_by_name(encoderName);
    require(encoder, "Requested audio encoder is unavailable");
    Conversion state;
    require(state.packet && state.encoded && state.frame, "Cannot allocate conversion buffers");
    state.decoder = avcodec_alloc_context3(decoder); require(state.decoder, "Cannot allocate decoder");
    check(avcodec_parameters_to_context(state.decoder, source->codecpar), "Read decoder parameters");
    state.decoder->thread_count = 1;
    check(avcodec_open2(state.decoder, decoder, nullptr), "Open audio decoder");
    require(state.decoder->sample_rate > 0 && state.decoder->ch_layout.nb_channels > 0, "Invalid input audio parameters");
    if (state.decoder->ch_layout.order == AV_CHANNEL_ORDER_UNSPEC) {
        const int count = state.decoder->ch_layout.nb_channels;
        av_channel_layout_uninit(&state.decoder->ch_layout);
        av_channel_layout_default(&state.decoder->ch_layout, count);
    }
    state.inputRate = state.decoder->sample_rate;
    state.inputFormat = state.decoder->sample_fmt;
    check(av_channel_layout_copy(&state.inputLayout, &state.decoder->ch_layout), "Copy input channel layout");
    state.encoder = avcodec_alloc_context3(encoder); require(state.encoder, "Cannot allocate encoder");
    auto enc = state.encoder;
    enc->sample_rate = nearestRate(rate ? rate : state.decoder->sample_rate, encoder);
    int channelCount = channels ? channels : state.decoder->ch_layout.nb_channels;
    if (format == "mp3" || format == "m4a") channelCount = std::min(channelCount, 2);
    require(channelCount <= 8, "More than eight output channels are unsupported");
    if (channelCount == state.decoder->ch_layout.nb_channels && state.decoder->ch_layout.order != AV_CHANNEL_ORDER_UNSPEC)
        check(av_channel_layout_copy(&enc->ch_layout, &state.decoder->ch_layout), "Copy encoder channel layout");
    else av_channel_layout_default(&enc->ch_layout, channelCount);
    const void* configurations = nullptr;
    check(avcodec_get_supported_config(enc, encoder, AV_CODEC_CONFIG_SAMPLE_FORMAT, 0, &configurations, nullptr), "Read encoder sample formats");
    const auto formats = static_cast<const AVSampleFormat*>(configurations);
    enc->sample_fmt = formats ? formats[0] : AV_SAMPLE_FMT_S16;
    if (format == "flac") {
        const int bits = state.decoder->bits_per_raw_sample ? state.decoder->bits_per_raw_sample : av_get_bytes_per_sample(state.decoder->sample_fmt) * 8;
        enc->sample_fmt = bits <= 16 ? AV_SAMPLE_FMT_S16 : AV_SAMPLE_FMT_S32;
        enc->bits_per_raw_sample = bits <= 16 ? 16 : 24;
    }
    enc->bit_rate = bitrate; enc->time_base = {1, enc->sample_rate}; enc->thread_count = 1;
    check(avformat_alloc_output_context2(&state.output, nullptr, muxer, outputPath.c_str()), "Allocate output container");
    require(state.output, "Cannot allocate output container");
    if (state.output->oformat->flags & AVFMT_GLOBALHEADER) enc->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
    check(avcodec_open2(enc, encoder, nullptr), "Open audio encoder");
    AVStream* destination = avformat_new_stream(state.output, nullptr); require(destination, "Cannot allocate output stream");
    destination->time_base = enc->time_base;
    check(avcodec_parameters_from_context(destination->codecpar, enc), "Set output audio parameters");
    state.file = std::make_unique<OutputFile>(outputPath, session.get(), format == "wav");
    auto buffer = static_cast<unsigned char*>(av_malloc(32768)); require(buffer, "Cannot allocate output buffer");
    state.io = avio_alloc_context(buffer, 32768, 1, state.file.get(), nullptr, OutputFile::write, OutputFile::seek);
    if (!state.io) { av_free(buffer); throw Failure("Cannot allocate output IO"); }
    state.output->pb = state.io; state.output->flags |= AVFMT_FLAG_CUSTOM_IO;
    check(avformat_write_header(state.output, nullptr), "Write output header");
    check(swr_alloc_set_opts2(&state.resampler, &enc->ch_layout, enc->sample_fmt, enc->sample_rate,
        &state.inputLayout, state.inputFormat, state.inputRate, 0, nullptr), "Allocate audio resampler");
    check(swr_init(state.resampler), "Initialize audio resampler");
    state.fifo = av_audio_fifo_alloc(enc->sample_fmt, channelCount, 1); require(state.fifo, "Cannot allocate audio FIFO");
    int64_t nextPts = 0, decodedSamples = 0;
    int lastProgress = -1;
    const int64_t duration = durationMs(input.context);
    auto progress = [&](float fraction) {
        cancelled(session);
        const int percent = static_cast<int>(fraction * 1000);
        if (percent != lastProgress) {
            env->CallVoidMethod(callback, progressMethod, fraction);
            if (env->ExceptionCheck()) throw Failure("Progress callback failed");
            lastProgress = percent;
        }
    };
    auto drainEncoder = [&]() {
        while (true) {
            cancelled(session);
            int result = avcodec_receive_packet(enc, state.encoded);
            if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) break;
            check(result, "Encode audio");
            av_packet_rescale_ts(state.encoded, enc->time_base, destination->time_base);
            state.encoded->stream_index = destination->index;
            result = av_interleaved_write_frame(state.output, state.encoded);
            av_packet_unref(state.encoded); check(result, "Write encoded audio");
            check(state.io->error, "Write output file");
        }
    };
    auto drainFifo = [&](bool final) {
        const int block = enc->frame_size > 0 ? enc->frame_size : 4096;
        while (av_audio_fifo_size(state.fifo) >= block || (final && av_audio_fifo_size(state.fifo) > 0)) {
            cancelled(session);
            const int available = std::min(block, av_audio_fifo_size(state.fifo));
            const bool small = encoder->capabilities & (AV_CODEC_CAP_VARIABLE_FRAME_SIZE | AV_CODEC_CAP_SMALL_LAST_FRAME);
            const int samples = available < block && !small ? block : available;
            auto frame = audioFrame(enc, samples);
            if (available < samples) check(av_samples_set_silence(frame->extended_data, available, samples - available, channelCount, enc->sample_fmt), "Pad last audio frame");
            require(av_audio_fifo_read(state.fifo, reinterpret_cast<void**>(frame->extended_data), available) == available, "Cannot read audio FIFO");
            frame->pts = nextPts; nextPts += samples;
            check(avcodec_send_frame(enc, frame.get()), "Send audio to encoder"); drainEncoder();
        }
    };
    auto resample = [&](AVFrame* frame) {
        cancelled(session);
        const int capacity = swr_get_out_samples(state.resampler, frame ? frame->nb_samples : 0);
        check(capacity, "Calculate resampler output");
        if (!capacity) return 0;
        auto output = audioFrame(enc, capacity);
        const uint8_t** samples = frame ? const_cast<const uint8_t**>(frame->extended_data) : nullptr;
        int count = swr_convert(state.resampler, output->extended_data, capacity, samples, frame ? frame->nb_samples : 0);
        check(count, "Resample audio");
        check(av_audio_fifo_realloc(state.fifo, av_audio_fifo_size(state.fifo) + count), "Grow audio FIFO");
        require(av_audio_fifo_write(state.fifo, reinterpret_cast<void**>(output->extended_data), count) == count, "Cannot write audio FIFO");
        drainFifo(false); return count;
    };
    auto drainDecoder = [&]() {
        while (true) {
            cancelled(session);
            int result = avcodec_receive_frame(state.decoder, state.frame);
            if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) break;
            check(result, "Decode audio");
            require(state.frame->sample_rate == state.inputRate && state.frame->format == state.inputFormat &&
                    av_channel_layout_compare(&state.frame->ch_layout, &state.inputLayout) == 0, "Audio parameters changed during decoding");
            decodedSamples += state.frame->nb_samples;
            resample(state.frame); av_frame_unref(state.frame);
            if (duration > 0) progress(std::min(0.99f, static_cast<float>(decodedSamples * 1000.0 / state.inputRate / duration)));
        }
    };
    progress(duration > 0 ? 0.f : -1.f);
    while (true) {
        cancelled(session);
        int result = av_read_frame(input.context, state.packet);
        if (result == AVERROR_EOF) break;
        check(result, "Read input audio");
        if (state.packet->stream_index == audio) {
            result = avcodec_send_packet(state.decoder, state.packet);
            if (result == AVERROR(EAGAIN)) { drainDecoder(); result = avcodec_send_packet(state.decoder, state.packet); }
            av_packet_unref(state.packet); check(result, "Send packet to decoder"); drainDecoder();
        } else av_packet_unref(state.packet);
    }
    check(avcodec_send_packet(state.decoder, nullptr), "Flush audio decoder"); drainDecoder();
    require(decodedSamples > 0, "Input contains no decodable audio samples");
    while (resample(nullptr) > 0) {};
    drainFifo(true);
    check(avcodec_send_frame(enc, nullptr), "Flush audio encoder"); drainEncoder();
    cancelled(session);
    check(av_write_trailer(state.output), "Finalize audio container");
    avio_flush(state.io); check(state.io->error, "Flush audio container");
    state.file->finish(); cancelled(session); progress(1); state.file->committed = true;
}
}

extern "C" JNIEXPORT jlong JNICALL Java_com_zhuanformat_app_NativeAudioBridge_createSession(JNIEnv* env, jobject) {
    try { auto session = std::make_shared<Session>(); auto id = nextSession.fetch_add(1); std::lock_guard<std::mutex> lock(sessionsMutex); sessions.emplace(id, session); return id; }
    catch (const std::exception& error) { raise(env, error); return 0; }
}
extern "C" JNIEXPORT void JNICALL Java_com_zhuanformat_app_NativeAudioBridge_cancelSession(JNIEnv*, jobject, jlong id) {
    std::lock_guard<std::mutex> lock(sessionsMutex); auto found = sessions.find(id); if (found != sessions.end()) found->second->cancelled = true;
}
extern "C" JNIEXPORT void JNICALL Java_com_zhuanformat_app_NativeAudioBridge_releaseSession(JNIEnv*, jobject, jlong id) {
    std::lock_guard<std::mutex> lock(sessionsMutex); auto found = sessions.find(id); if (found != sessions.end()) { found->second->cancelled = true; sessions.erase(found); }
}
extern "C" JNIEXPORT jstring JNICALL Java_com_zhuanformat_app_NativeAudioBridge_probe(JNIEnv* env, jobject, jlong id, jstring file) {
    try {
        auto session = findSession(id); Busy busy(session); Input input(path(env, file), session.get()); cancelled(session);
        int width = 0, height = 0, rotation = 0, rate = 0, channels = 0; bool video = false, audio = false;
        for (unsigned i = 0; i < input.context->nb_streams; ++i) {
            auto parameters = input.context->streams[i]->codecpar;
            if (!audio && parameters->codec_type == AVMEDIA_TYPE_AUDIO) { audio = true; rate = parameters->sample_rate; channels = parameters->ch_layout.nb_channels; }
            if (!video && parameters->codec_type == AVMEDIA_TYPE_VIDEO && !(input.context->streams[i]->disposition & AV_DISPOSITION_ATTACHED_PIC)) {
                video = true; width = parameters->width; height = parameters->height;
                const auto side = av_packet_side_data_get(parameters->coded_side_data, parameters->nb_coded_side_data, AV_PKT_DATA_DISPLAYMATRIX);
                if (side && side->size >= 9 * sizeof(int32_t)) {
                    double angle = -av_display_rotation_get(reinterpret_cast<const int32_t*>(side->data));
                    if (std::isfinite(angle)) rotation = (static_cast<int>(std::lround(angle)) % 360 + 360) % 360;
                }
            }
        }
        const bool swap = rotation == 90 || rotation == 270;
        std::ostringstream json; json << "{\"durationMs\":" << durationMs(input.context) << ",\"width\":" << width << ",\"height\":" << height
            << ",\"hasVideo\":" << (video ? "true" : "false") << ",\"hasAudio\":" << (audio ? "true" : "false") << ",\"rotation\":" << rotation
            << ",\"displayWidth\":" << (swap ? height : width) << ",\"displayHeight\":" << (swap ? width : height) << ",\"sampleRate\":" << rate << ",\"channels\":" << channels << "}";
        cancelled(session); return env->NewStringUTF(json.str().c_str());
    } catch (const std::exception& error) { raise(env, error); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL Java_com_zhuanformat_app_NativeAudioBridge_convert(JNIEnv* env, jobject, jlong id, jstring input, jstring output,
        jstring formatValue, jint rate, jint channels, jint bitrate, jobject callback) {
    try {
        auto session = findSession(id); Busy busy(session);
        std::string inputPath = path(env, input); std::string outputPath = path(env, output);
        require(inputPath != outputPath, "Input and output paths must differ");
        // Only remove files that this call is permitted to create; never unlink the input.
        require(access(outputPath.c_str(), F_OK) != 0, "Output file already exists");
        require(formatValue != nullptr, "Output format is null");
        const char* chars = env->GetStringUTFChars(formatValue, nullptr); require(chars, "Cannot read output format");
        std::string format(chars); env->ReleaseStringUTFChars(formatValue, chars);
        convert(env, session, inputPath, outputPath, format, rate, channels, bitrate, callback);
    } catch (const std::exception& error) { raise(env, error); }
}
