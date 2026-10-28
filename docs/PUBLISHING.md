# 推送源码到 GitHub

按当前安排，先保留本地源码，等用户创建仓库并提供地址后再推送。当前没有创建远端仓库、推送源码或发布 Release；也不制作 APK 或源码压缩包。

已确认 GitHub 账号为 `GZR-zhuanjia`。现有连接可以读取账号信息，但创建仓库接口返回 `Resource not accessible by integration`（HTTP 403），因此需要用户自行创建一个独立的空仓库。创建时不要初始化 README、许可证或 `.gitignore`，可自行选择仓库可见性。

收到新仓库地址后，在项目目录核对本地提交与远端，再执行推送：

```bash
git status
git remote -v
git ls-remote --heads --tags https://github.com/GZR-zhuanjia/新仓库名.git
```

确认工作区已提交、仓库属于用户且远端没有分支或标签后，才设置 `origin` 并推送当前分支：

```bash
git remote add origin https://github.com/GZR-zhuanjia/新仓库名.git
git push -u origin HEAD
```

若已存在 `origin`，先核对它的地址；不要直接覆盖已有远端，不使用强制推送。上传前也应确认本地 SDK 路径、构建缓存、密钥和凭据均未被 Git 跟踪。推送还需要对新仓库拥有写入权限。
