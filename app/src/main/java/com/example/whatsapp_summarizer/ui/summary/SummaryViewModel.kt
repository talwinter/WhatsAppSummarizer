package com.example.whatsapp_summarizer.ui.summary

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.whatsapp_summarizer.data.model.Message
import com.example.whatsapp_summarizer.data.repository.MessageRepository
import kotlinx.coroutines.launch
import java.util.*

class SummaryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as com.example.whatsapp_summarizer.WhatsAppSummarizerApp).repository
    
    private val _summary = MutableLiveData<String>()
    val summary: LiveData<String> = _summary

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    fun generateSummary(
        chatName: String, 
        apiKey: String,
        inHebrew: Boolean = false,
        startTime: Long? = null,
        endTime: Long? = null
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            
            try {
                val messages = if (startTime != null && endTime != null) {
                    repository.getMessagesForTimeRange(chatName, startTime, endTime)
                } else {
                    repository.getTodayMessages(chatName)
                }
                
                if (messages.isEmpty()) {
                    _summary.value = if (inHebrew) {
                        "לא נמצאו הודעות בטווח הזמן הנבחר ב-'$chatName'."
                    } else {
                        "No messages found in the selected time range in '$chatName'."
                    }
                    _isLoading.value = false
                    return@launch
                }

                val summary = AiSummarizer.summarizeMessages(messages, apiKey, inHebrew)
                _summary.value = summary
            } catch (e: Exception) {
                val errorMsg = when {
                    e.message?.contains("401") == true -> {
                        if (inHebrew) "מפתח API לא תקין. אנא בדוק את מפתח ה-OpenAI שלך בהגדרות."
                        else "Invalid API key. Please check your OpenAI API key in Settings."
                    }
                    e.message?.contains("429") == true -> {
                        if (inHebrew) "חרגת ממגבלת הבקשות. יותר מדי בקשות. אנא המתן דקה ונסה שוב."
                        else "Rate limit exceeded. Too many requests. Please wait a minute and try again."
                    }
                    e.message?.contains("500") == true || e.message?.contains("502") == true || e.message?.contains("503") == true -> {
                        if (inHebrew) "שגיאת שרת OpenAI. אנא נסה שוב מאוחר יותר."
                        else "OpenAI server error. Please try again later."
                    }
                    e.message?.contains("No route to host") == true || e.message?.contains("UnknownHostException") == true -> {
                        if (inHebrew) "אין חיבור לאינטרנט. אנא בדוק את החיבור שלך ונסה שוב."
                        else "No internet connection. Please check your network and try again."
                    }
                    e.message?.contains("timeout") == true || e.message?.contains("Timeout") == true -> {
                        if (inHebrew) "הבקשה נכשלה בזמן הקצוב. אנא בדוק את החיבור ונסה שוב."
                        else "Request timed out. Please check your connection and try again."
                    }
                    else -> {
                        val msg = e.message ?: e.cause?.message ?: "Unknown error (${e.javaClass.simpleName})"
                        if (inHebrew) "שגיאה: $msg" else "Error: $msg"
                    }
                }
                _error.value = errorMsg
            } finally {
                _isLoading.value = false
            }
        }
    }
}
