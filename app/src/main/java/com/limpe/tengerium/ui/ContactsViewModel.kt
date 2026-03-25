package com.limpe.tengerium.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.limpe.tengerium.TengeriumApp
import com.limpe.tengerium.domain.model.Contact
import kotlinx.coroutines.flow.StateFlow

class  ContactsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as TengeriumApp).repository

    val contacts: StateFlow<List<Contact>> = repository.contacts

    fun addContact(account: String) {
        repository.addContact(account)
    }

    fun removeContact(account: String) {
        repository.removeContact(account)
    }
}
