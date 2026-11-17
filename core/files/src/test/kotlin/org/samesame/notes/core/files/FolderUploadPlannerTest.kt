package org.samesame.notes.core.files

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class FolderUploadPlannerTest {
    private val sampleSource = object : UploadTreeSource<String> {
        val asked = ArrayList<String?>()
        override suspend fun children(parentId: String?): List<UploadChild<String>> {
            asked += parentId
            return when (parentId) {
                null -> listOf(UploadChild("d1", "Pictures", true), UploadChild("f1", "a.txt", false))
                "d1" -> listOf(UploadChild("f2", "p.png", false), UploadChild("d2", "2024", true))
                "d2" -> listOf(UploadChild("f3", "b.jpg", false))
                else -> emptyList()
            }
        }
    }

    @Test fun `readUploadTree walks folders depth first`() = runBlocking {
        val tree = readUploadTree(sampleSource)
        assertThat(tree.map { it.name }).containsExactly("Pictures", "a.txt").inOrder()
        val pictures = tree.first { it.isDirectory }
        assertThat(pictures.children.map { it.name }).containsExactly("p.png", "2024").inOrder()
        assertThat(pictures.children.first { it.isDirectory }.children.single().name).isEqualTo("b.jpg")
        assertThat(sampleSource.asked).containsExactly(null, "d1", "d2").inOrder()
    }

    @Test fun `plan maps the tree to folder paths and file targets`() = runBlocking {
        val plan = planFolderUpload(readUploadTree(sampleSource))
        assertThat(plan.folders.map { it.path }).containsExactly(listOf("Pictures"), listOf("Pictures", "2024")).inOrder()
        assertThat(plan.files.map { Triple(it.folderPath, it.name, it.value) }).containsExactly(
            Triple(listOf("Pictures"), "p.png", "f2"),
            Triple(listOf("Pictures", "2024"), "b.jpg", "f3"),
            Triple(emptyList<String>(), "a.txt", "f1"),
        ).inOrder()
        assertThat(plan.skipped).isEmpty()
    }

    @Test fun `plan gives sibling folders unique names against existing and new folders`() {
        val roots = listOf(
            UploadNode("Photos", "d1", true),
            UploadNode("Photos", "d2", true),
            UploadNode("photos", "d3", true),
        )
        val plan = planFolderUpload(roots, existingNames = mapOf(emptyList<String>() to setOf("Photos", "Photos (2)")))
        assertThat(plan.folders.map { it.name }).containsExactly("Photos (3)", "Photos (4)", "photos (5)").inOrder()
    }

    @Test fun `plan caps folder depth and reports the rest`() {
        var node = UploadNode("leaf", "f", false)
        repeat(52) { i -> node = UploadNode("d$i", "d$i", true, listOf(node)) }
        val plan = planFolderUpload(listOf(node))
        assertThat(plan.folders).hasSize(50)
        assertThat(plan.folders.last().name).isEqualTo("d2")
        assertThat(plan.skipped).containsExactly(UploadSkip("d1", UploadSkipReason.TooDeep))
    }

    @Test fun `plan caps the number of files`() {
        val roots = (1..4).map { UploadNode("f$it.txt", "f$it", false) }
        val plan = planFolderUpload(roots, maxFiles = 2)
        assertThat(plan.files.map { it.name }).containsExactly("f1.txt", "f2.txt").inOrder()
        assertThat(plan.skipped).containsExactly(
            UploadSkip("f3.txt", UploadSkipReason.TooManyFiles),
            UploadSkip("f4.txt", UploadSkipReason.TooManyFiles),
        ).inOrder()
    }

    @Test fun `plan sanitises names and skips unusable folder names`() {
        val plan = planFolderUpload(
            listOf(
                UploadNode("A/B", "d1", true, listOf(UploadNode("x\u0000y.txt", "f1", false))),
                UploadNode("...", "d2", true),
            ),
        )
        assertThat(plan.folders.map { it.name }).containsExactly("A_B")
        assertThat(plan.files.single().name).isEqualTo("xy.txt")
        assertThat(plan.skipped).containsExactly(UploadSkip("...", UploadSkipReason.EmptyName))
    }
}
