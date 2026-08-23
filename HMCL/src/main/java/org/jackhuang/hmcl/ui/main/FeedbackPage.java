/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.ui.main;

import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.theme.Themes;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.WeakListenerHolder;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LineButton;
import org.jackhuang.hmcl.ui.construct.SpinnerPane;
import org.jetbrains.annotations.NotNullByDefault;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

import org.jackhuang.hmcl.Metadata;

/// Provides the maintained project feedback destination.
@NotNullByDefault
public class FeedbackPage extends SpinnerPane {
    /// Keeps the theme listener alive for the lifetime of this page.
    private final WeakListenerHolder holder = new WeakListenerHolder();

    /// Creates the feedback page with the CE GitHub contact destination.
    public FeedbackPage() {
        VBox content = new VBox();
        content.getStyleClass().add("spinner-pane-content");
        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        FXUtils.smoothScrolling(scrollPane);
        setContent(scrollPane);

        ComponentList feedback = new ComponentList();
        {
            var github = LineButton.createExternalLinkButton(Metadata.CONTACT_URL);
            github.setLargeTitle(true);
            github.setTitle(i18n("contact.feedback.github"));
            github.setSubtitle(i18n("contact.feedback.github.statement"));

            holder.add(FXUtils.onWeakChangeAndOperate(Themes.darkModeProperty(), darkMode -> {
                github.setLeading(darkMode
                        ? FXUtils.newBuiltinImage("/assets/img/github-white.png")
                        : FXUtils.newBuiltinImage("/assets/img/github.png"));
            }));

            feedback.getContent().setAll(github);
        }

        content.getChildren().addAll(
                ComponentList.createComponentListTitle(i18n("contact.feedback")),
                feedback
        );
    }
}
