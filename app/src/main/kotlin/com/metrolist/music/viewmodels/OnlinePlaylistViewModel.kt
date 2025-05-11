package com.metrolist.music.viewmodels

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.utils.completed
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnlinePlaylistViewModel @Inject constructor(
    @ApplicationContext context: Context,
    savedStateHandle: SavedStateHandle,
    database: MusicDatabase
) : ViewModel() {
    private val playlistId = savedStateHandle.get<String>("playlistId")!!

    val playlist = MutableStateFlow<PlaylistItem?>(null)
    val playlistSongs = MutableStateFlow<List<SongItem>>(emptyList())

    val dbPlaylist = database.playlistByBrowseId(playlistId)
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    private val hideExplicitFlow = context.dataStore.data
        .map { it[HideExplicitKey] ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val hideExplicit = hideExplicitFlow.value

            YouTube.playlist(playlistId).completed()
                .onSuccess { playlistPage ->
                    playlist.value = playlistPage.playlist
                    playlistSongs.value =
                        if (hideExplicit)
                            playlistPage.songs.filterNot { it.explicit == true }
                        else
                            playlistPage.songs
                }.onFailure {
                    reportException(it)
                }
        }
    }
}
