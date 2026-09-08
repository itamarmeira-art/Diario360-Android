package com.diario360.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        NotificationHelper.show(context, intent.getStringExtra("title") ?: "Diário 360", intent.getStringExtra("text") ?: "Sua reflexão estoica está pronta.", intent.getIntExtra("requestCode", 1000))
    }
}
