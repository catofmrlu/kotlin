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

package org.jetbrains.kotlin.idea.highlighter;

import com.intellij.lang.annotation.AnnotationHolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.psi.JetExpressionWithLabel;
import org.jetbrains.kotlin.psi.JetSimpleNameExpression;
import org.jetbrains.kotlin.psi.JetVisitorVoid;

class LabelsHighlightingVisitor extends JetVisitorVoid {
    private final AnnotationHolder holder;

    LabelsHighlightingVisitor(AnnotationHolder holder) {
        this.holder = holder;
    }

    @Override
    public void visitExpressionWithLabel(@NotNull JetExpressionWithLabel expression) {
        JetSimpleNameExpression targetLabel = expression.getTargetLabel();
        if (targetLabel != null) {
            JetPsiChecker.highlightName(holder, targetLabel, JetHighlightingColors.LABEL);
        }
    }
}
