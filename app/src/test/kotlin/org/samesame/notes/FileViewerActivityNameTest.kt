package org.samesame.notes

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.feature.files.viewer.LibraryFileViewer

class FileViewerActivityNameTest {
    /** `openLibraryFile` starts the host by class name, because feature modules can't see app classes. */
    @Test fun hostActivityNameMatchesTheClass() {
        assertThat(LibraryFileViewer.HOST_ACTIVITY).isEqualTo(FileViewerActivity::class.java.name)
    }
}
