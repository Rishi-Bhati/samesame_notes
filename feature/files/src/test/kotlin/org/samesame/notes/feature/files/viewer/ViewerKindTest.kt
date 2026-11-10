package org.samesame.notes.feature.files.viewer

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.files.FileCategory

class ViewerKindTest {
    @Test fun picksTheViewerByType() {
        assertThat(ViewerKind.of(image("a"))).isEqualTo(ViewerKind.Image)
        assertThat(ViewerKind.of(libraryFile("p", "p.pdf", "application/pdf", FileCategory.Pdf))).isEqualTo(ViewerKind.Pdf)
        assertThat(ViewerKind.of(libraryFile("t"))).isEqualTo(ViewerKind.Text)
        assertThat(ViewerKind.of(libraryFile("m", "m.md", "text/markdown", FileCategory.Markdown))).isEqualTo(ViewerKind.Text)
        assertThat(ViewerKind.of(libraryFile("s", "s.mp3", "audio/mpeg", FileCategory.Audio))).isEqualTo(ViewerKind.Audio)
    }

    @Test fun textLikeFilesOutsideTheTextCategory() {
        assertThat(ViewerKind.of(libraryFile("j", "j.json", "application/json", FileCategory.Other))).isEqualTo(ViewerKind.Text)
        assertThat(ViewerKind.of(libraryFile("k", "k.gradle", "application/octet-stream", FileCategory.Other))).isEqualTo(ViewerKind.Text)
        assertThat(ViewerKind.of(libraryFile("h", "h.html", "text/html", FileCategory.Document))).isEqualTo(ViewerKind.Text)
    }

    @Test fun everythingElseGoesToTheSystem() {
        assertThat(ViewerKind.of(libraryFile("v", "v.mp4", "video/mp4", FileCategory.Video))).isEqualTo(ViewerKind.External)
        assertThat(ViewerKind.of(libraryFile("z", "z.zip", "application/zip", FileCategory.Archive))).isEqualTo(ViewerKind.External)
        assertThat(ViewerKind.of(libraryFile("d", "d.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", FileCategory.Document))).isEqualTo(ViewerKind.External)
        assertThat(ViewerKind.of(libraryFile("a", "a.apk", "application/vnd.android.package-archive", FileCategory.Other))).isEqualTo(ViewerKind.External)
        // An image type the viewer can't decode.
        assertThat(ViewerKind.of(libraryFile("s", "s.svg", "image/svg+xml", FileCategory.Image))).isEqualTo(ViewerKind.External)
    }
}
