package org.fossify.contacts.fragments

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import org.fossify.commons.extensions.areSystemAnimationsEnabled
import org.fossify.commons.extensions.hideKeyboard
import org.fossify.commons.models.contacts.Contact
import org.fossify.contacts.activities.EditContactActivity
import org.fossify.contacts.activities.InsertOrEditContactActivity
import org.fossify.contacts.activities.MainActivity
import org.fossify.contacts.activities.SimpleActivity
import org.fossify.contacts.adapters.ContactsAdapter
import org.fossify.contacts.databinding.FragmentContactsBinding
import org.fossify.contacts.databinding.FragmentLettersLayoutBinding
import org.fossify.contacts.extensions.config
import org.fossify.contacts.extensions.viewContact
import org.fossify.contacts.helpers.LOCATION_CONTACTS_TAB
import org.fossify.contacts.interfaces.RefreshContactsListener

class ContactsFragment(context: Context, attributeSet: AttributeSet) : MyViewPagerFragment<MyViewPagerFragment.LetterLayout>(context, attributeSet) {

    private lateinit var binding: FragmentContactsBinding

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = FragmentContactsBinding.bind(this)
        innerBinding = LetterLayout(FragmentLettersLayoutBinding.bind(binding.root))
    }

    override fun fabClicked() {
        activity?.hideKeyboard()
        Intent(context, EditContactActivity::class.java).apply {
            context.startActivity(this)
        }
    }

    override fun placeholderClicked() {
        if (activity is MainActivity) {
            (activity as MainActivity).showFilterDialog()
        } else if (activity is InsertOrEditContactActivity) {
            (activity as InsertOrEditContactActivity).showFilterDialog()
        }
    }

    fun setupContactsAdapter(contacts: List<Contact>) {
        // 1. Sort: Favorites at the top, then alphabetical
        val sortedContacts = contacts.sortedWith(
            compareByDescending<Contact> { it.starred }
                .thenBy { it.getNameToDisplay().lowercase() }
        )

        setupViewVisibility(sortedContacts.isNotEmpty())
        val currAdapter = innerBinding.fragmentList.adapter

        // 2. Add the "Blank Space" Decoration
        // We remove existing decorations first to prevent spacing from stacking on refresh
        while (innerBinding.fragmentList.itemDecorationCount > 0) {
            innerBinding.fragmentList.removeItemDecorationAt(0)
        }

        innerBinding.fragmentList.addItemDecoration(object : androidx.recyclerview.widget.RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, view: View, parent: androidx.recyclerview.widget.RecyclerView, state: androidx.recyclerview.widget.RecyclerView.State) {
                val position = parent.getChildAdapterPosition(view)
                if (position == -1 || position >= sortedContacts.size - 1) return

                val currentContact = sortedContacts[position]
                val nextContact = sortedContacts[position + 1]

                val currentLetter = currentContact.getNameToDisplay().firstOrNull()?.uppercaseChar()
                val nextLetter = nextContact.getNameToDisplay().firstOrNull()?.uppercaseChar()

                // If this is the last favorite before the regular contacts, add a gap
                if (currentContact.starred == 1 && nextContact.starred == 0) {
                    outRect.bottom = 80 // Change this number to adjust the height of the blank space
                } else if (currentLetter != nextLetter) {
                    outRect.bottom = 48 // Add a small space between different leading alphabets
                }
            }
        })

        // 3. Standard Adapter Logic
        if (currAdapter == null || forceListRedraw) {
            forceListRedraw = false
            ContactsAdapter(
                activity = activity as SimpleActivity,
                contactItems = sortedContacts.toMutableList(),
                refreshListener = activity as RefreshContactsListener,
                location = LOCATION_CONTACTS_TAB,
                removeListener = null,
                recyclerView = innerBinding.fragmentList,
                enableDrag = false,
                itemClick = { (activity as RefreshContactsListener).contactClicked(it as Contact) },
                profileIconClick = { activity?.viewContact(it as Contact) }
            ).apply {
                innerBinding.fragmentList.adapter = this
            }

            if (context.areSystemAnimationsEnabled) {
                innerBinding.fragmentList.scheduleLayoutAnimation()
            }
        } else {
            (currAdapter as ContactsAdapter).apply {
                startNameWithSurname = context.config.startNameWithSurname
                showPhoneNumbers = context.config.showPhoneNumbers
                showContactThumbnails = context.config.showContactThumbnails
                updateItems(sortedContacts)
            }
        }
    }
}
