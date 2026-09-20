package com.lunaexplorer.app.playback

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.ProviderRegistry

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlaybackSource(val uri: Uri, val dataSources: DataSource.Factory) {
    companion object {
        fun create(context: Context, providers: ProviderRegistry, entry: Entry): PlaybackSource {
            val provider = providers.provider(entry.ref)
            // A content URI would route back through Luna's own kernel proxy descriptor.
            val uri = Uri.Builder().scheme("luna-media").authority("playback").appendPath(entry.name).build()
            val storage = DataSource.Factory { StorageDataSource(provider, entry.ref) }
            return PlaybackSource(uri, DefaultDataSource.Factory(context.applicationContext, storage))
        }
    }
}
