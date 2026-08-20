package com.whisperonnx.utils;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.TextView;

import com.whisperonnx.R;
import com.whisperonnx.asr.WordReplacements;

import java.util.List;

public class WordReplacementAdapter extends BaseAdapter {

    public interface OnDeleteListener {
        void onDelete(int position);
    }

    private final Context context;
    private final List<WordReplacements.Entry> entries;
    private final OnDeleteListener deleteListener;

    public WordReplacementAdapter(Context context, List<WordReplacements.Entry> entries, OnDeleteListener deleteListener) {
        this.context = context;
        this.entries = entries;
        this.deleteListener = deleteListener;
    }

    @Override
    public int getCount() {
        return entries.size();
    }

    @Override
    public WordReplacements.Entry getItem(int position) {
        return entries.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.item_word_replacement, parent, false);
        }

        WordReplacements.Entry entry = entries.get(position);
        TextView tvEntry = convertView.findViewById(R.id.tv_replacement_entry);
        ImageButton btnDelete = convertView.findViewById(R.id.btn_delete_replacement);

        tvEntry.setText(entry.from + "  →  " + entry.to);
        btnDelete.setOnClickListener(v -> deleteListener.onDelete(position));

        return convertView;
    }
}
