package com.metrolist.music.viewmodels

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.MyTopFilter
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.utils.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class TopPlaylistViewModel
@Inject
constructor(
    @ApplicationContext context: Context,
    database: MusicDatabase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val top = savedStateHandle.get<String>("top")!!

    val topPeriod = MutableStateFlow(MyTopFilter.ALL_TIME)

    private val hideExplicitFlow = context.dataStore.data
        .map { it[HideExplicitKey] ?: false }
        .distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    val topSongs =
        combine(
            topPeriod,
            hideExplicitFlow
        ) { period, hideExplicit ->
            period to hideExplicit
        }.flatMapLatest { (period, hideExplicit) ->
            database.mostPlayedSongs(period.toTimeMillis(), top.toInt())
                .map { songs ->
                    if (hideExplicit) songs.filterNot { it.song.explicit == true } else songs
                }
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
}
