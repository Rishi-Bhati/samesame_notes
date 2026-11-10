package org.samesame.notes.core.data.files

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.samesame.notes.core.files.provider.LibraryContentProvider
import org.samesame.notes.core.files.provider.LibraryFileLookup

/** The app's Files provider (declared in this module's manifest): ids resolve through the Files repository. */
class AppLibraryContentProvider : LibraryContentProvider() {
    override fun lookup(): LibraryFileLookup =
        EntryPointAccessors.fromApplication(requireNotNull(context).applicationContext, LibraryProviderEntryPoint::class.java)
            .libraryFileLookup()

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface LibraryProviderEntryPoint {
        fun libraryFileLookup(): LibraryFileLookup
    }
}
