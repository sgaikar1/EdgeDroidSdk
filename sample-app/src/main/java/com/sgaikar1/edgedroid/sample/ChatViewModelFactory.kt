package com.sgaikar1.edgedroid.sample

import androidx.lifecycle.ViewModelProvider

class ChatViewModelFactory(
    private val app: EdgeDroidApp,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        return ChatViewModel(app) as T
    }
}
