/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.console.highlight

import com.intellij.execution.console.LanguageConsoleImpl
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.console.KotlinConsoleRunner
import org.jetbrains.kotlin.console.gutter.KotlinConsoleIndicatorRenderer
import org.jetbrains.kotlin.console.gutter.ReplIcons

public class KotlinHistoryHighlighter(private val runner: KotlinConsoleRunner ) {
    private val consoleView: LanguageConsoleImpl by lazy { runner.consoleView as LanguageConsoleImpl }

    fun printNewCommandInHistory(trimmedCommandText: String, lastCommandType: ReplOutputType) {
        val historyEditor = consoleView.historyViewer

        addLineBreakIfNeeded(historyEditor, lastCommandType)

        val consoleEditor = consoleView.consoleEditor
        val consoleDocument = consoleEditor.document
        consoleDocument.setText(trimmedCommandText)

        val startOffset = historyEditor.document.textLength
        val endOffset = startOffset + trimmedCommandText.length()

        LanguageConsoleImpl.printWithHighlighting(consoleView, consoleEditor, TextRange(0, consoleDocument.textLength))
        consoleView.flushDeferredText()
        EditorUtil.scrollToTheEnd(historyEditor)
        consoleDocument.setText("")

        historyEditor.markupModel let {
            it.addRangeHighlighter(startOffset, endOffset, HighlighterLayer.LAST, null, HighlighterTargetArea.EXACT_RANGE)
        } apply {
            gutterIconRenderer = KotlinConsoleIndicatorRenderer(ReplIcons.COMMAND_MARKER)
        }
    }

    private fun addLineBreakIfNeeded(historyEditor: EditorEx, lastCommandType: ReplOutputType) {
        val historyDocument = historyEditor.document
        val historyText = historyDocument.text
        val textLength = historyText.length()

        if (!historyText.endsWith('\n')) {
            historyDocument.insertString(textLength, "\n")

            if (textLength == 0) // this will work first time after 'Clear all' action
                runner.addGutterIndicator(historyEditor, ReplIcons.HISTORY_INDICATOR)
            else if (lastCommandType != ReplOutputType.INCOMPLETE)
                historyDocument.insertString(textLength + 1, "\n")

        } else if (!historyText.endsWith("\n\n")) {
            historyDocument.insertString(textLength, "\n")
        }
    }
}