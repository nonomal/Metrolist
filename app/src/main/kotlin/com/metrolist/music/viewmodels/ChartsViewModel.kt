package com.metrolist.music.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.pages.ChartsPage
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.utils.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChartsViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val _chartsPage = MutableStateFlow<ChartsPage?>(null)
    val chartsPage = _chartsPage.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val hideExplicitFlow = context.dataStore.data
        .map { it[HideExplicitKey] ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun loadCharts() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            val hideExplicit = hideExplicitFlow.value

            YouTube.getChartsPage()
                .onSuccess { page ->
                    val filteredSections = if (hideExplicit) {
                        page.sections.map { section ->
                            section.copy(items = section.items.filterNot { it.explicit == true })
                        }
                    } else {
                        page.sections
                    }

                    _chartsPage.value = page.copy(sections = filteredSections)
                }
                .onFailure { e ->
                    _error.value = "Failed to load charts: ${e.message}"
                }

            _isLoading.value = false
        }
    }

    fun loadMore() {
        viewModelScope.launch {
            _chartsPage.value?.continuation?.let { continuation ->
                _isLoading.value = true

                val hideExplicit = hideExplicitFlow.value

                YouTube.getChartsPage(continuation)
                    .onSuccess { newPage ->
                        val filteredNewSections = if (hideExplicit) {
                            newPage.sections.map { section ->
                                section.copy(items = section.items.filterNot { it.explicit == true })
                            }
                        } else {
                            newPage.sections
                        }

                        _chartsPage.value = _chartsPage.value?.copy(
                            sections = _chartsPage.value?.sections.orEmpty() + filteredNewSections,
                            continuation = newPage.continuation
                        )
                    }
                    .onFailure { e ->
                        _error.value = "Failed to load more: ${e.message}"
                    }

                _isLoading.value = false
            }
        }
    }
}
