package com.zhuanformat.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComposeSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun switchesMediaTypesAndExplainsSupportedFormats() {
        compose.onNodeWithText("音频", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("main_list").performScrollToNode(hasText("M4A"))
        compose.onNodeWithText("M4A").assertIsDisplayed()
        compose.onNodeWithText("WAV").performClick()
        compose.onNodeWithTag("main_list").performScrollToNode(hasText("图片"))
        compose.onNodeWithText("图片", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("main_list").performScrollToNode(hasText("PNG"))
        compose.onNodeWithText("PNG").assertIsDisplayed()
        compose.onNodeWithTag("main_list").performScrollToNode(hasContentDescription("支持格式与使用说明"))
        compose.onNodeWithContentDescription("支持格式与使用说明").performClick()
        compose.onNodeWithText("在本地，安心转换").assertIsDisplayed()
        compose.onNodeWithText("知道了").performClick()
    }
}
