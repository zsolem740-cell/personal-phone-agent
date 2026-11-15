package hu.personal.phoneagent

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView

class MessageAdapter(
    private val context: Context,
    private val messages: List<ChatMessage>
) : BaseAdapter() {

    override fun getCount(): Int = messages.size

    override fun getItem(position: Int): ChatMessage = messages[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getViewTypeCount(): Int = 2

    override fun getItemViewType(position: Int): Int =
        if (messages[position].fromUser) 1 else 0

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val message = messages[position]
        val layout = if (message.fromUser) {
            R.layout.item_message_user
        } else {
            R.layout.item_message_assistant
        }
        val view = convertView ?: LayoutInflater.from(context).inflate(layout, parent, false)
        view.findViewById<TextView>(R.id.message_text).text = message.text
        view.findViewById<TextView>(R.id.message_time).text = message.time
        return view
    }
}
