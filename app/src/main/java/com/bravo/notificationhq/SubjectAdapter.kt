package com.bravo.notificationhq

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SubjectAdapter(private val subjectList: List<SubjectModel>) :
    RecyclerView.Adapter<SubjectAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvSubjectName: TextView = itemView.findViewById(R.id.tvSubjectName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_subject, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val subject = subjectList[position]
        holder.tvSubjectName.text = subject.name

        // When you click a subject card...
        holder.itemView.setOnClickListener {
            // TODO: We will write DetailActivity in the next step!
             val intent = Intent(holder.itemView.context, DetailActivity::class.java)
             intent.putExtra("SUBJECT_NAME", subject.name)
             intent.putExtra("TARGET_GROUP", subject.targetGroup)
             holder.itemView.context.startActivity(intent)
        }
    }

    override fun getItemCount(): Int = subjectList.size
}