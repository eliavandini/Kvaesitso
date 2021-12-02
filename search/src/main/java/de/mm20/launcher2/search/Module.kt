package de.mm20.launcher2.search

import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val searchModule = module {
    single { SearchRepository() }
    viewModel { SearchViewModel(get()) }
    single { WebsearchRepository(androidContext()) }
    viewModel { WebsearchViewModel(get()) }
}