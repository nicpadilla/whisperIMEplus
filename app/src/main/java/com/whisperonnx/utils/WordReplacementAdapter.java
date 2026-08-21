package com.whisperonnx.utils;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.whisperonnx.R;
import com.whisperonnx.asr.WordReplacements;

import java.util.UUID;

public final class WordReplacementAdapter extends
        ListAdapter<WordReplacements.Entry, WordReplacementAdapter.ViewHolder> {
    public interface Listener {
        void onEdit(String id);
        void onEnabledChanged(String id, boolean enabled);
        void onMove(String id, int delta);
        void onDelete(String id);
    }

    private static final DiffUtil.ItemCallback<WordReplacements.Entry> DIFF =
            new DiffUtil.ItemCallback<WordReplacements.Entry>() {
                @Override public boolean areItemsTheSame(@NonNull WordReplacements.Entry oldItem,
                                                         @NonNull WordReplacements.Entry newItem) {
                    return oldItem.id.equals(newItem.id);
                }

                @Override public boolean areContentsTheSame(@NonNull WordReplacements.Entry oldItem,
                                                            @NonNull WordReplacements.Entry newItem) {
                    return oldItem.from.equals(newItem.from)
                            && oldItem.to.equals(newItem.to)
                            && oldItem.enabled == newItem.enabled;
                }
            };

    private final Listener listener;

    public WordReplacementAdapter(Listener listener) {
        super(DIFF);
        this.listener = listener;
        setHasStableIds(true);
    }

    @Override public long getItemId(int position) {
        return stableId(getItem(position).id);
    }

    private static long stableId(String id) {
        long value;
        try {
            UUID uuid = UUID.fromString(id);
            value = uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits();
        } catch (IllegalArgumentException malformedId) {
            // FNV-1a gives legacy/custom IDs a deterministic 64-bit identity.
            value = 0xcbf29ce484222325L;
            for (int index = 0; index < id.length(); index++) {
                value ^= id.charAt(index);
                value *= 0x100000001b3L;
            }
        }
        return value == RecyclerView.NO_ID ? Long.MIN_VALUE : value;
    }

    @NonNull @Override public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_word_replacement, parent, false);
        return new ViewHolder(view);
    }

    @Override public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(getItem(position), position, getItemCount(), listener);
    }

    static final class ViewHolder extends RecyclerView.ViewHolder {
        private final CheckBox enabled;
        private final TextView entryText;
        private final ImageButton edit;
        private final ImageButton moveUp;
        private final ImageButton moveDown;
        private final ImageButton delete;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            enabled = itemView.findViewById(R.id.replacement_enabled);
            entryText = itemView.findViewById(R.id.tv_replacement_entry);
            edit = itemView.findViewById(R.id.btn_edit_replacement);
            moveUp = itemView.findViewById(R.id.btn_move_replacement_up);
            moveDown = itemView.findViewById(R.id.btn_move_replacement_down);
            delete = itemView.findViewById(R.id.btn_delete_replacement);
        }

        void bind(WordReplacements.Entry entry, int position, int count, Listener listener) {
            entryText.setText(itemView.getContext().getString(
                    R.string.replacement_entry_format, entry.from, entry.to));
            enabled.setOnCheckedChangeListener(null);
            enabled.setChecked(entry.enabled);
            enabled.setContentDescription(itemView.getContext().getString(
                    R.string.replacement_enabled_content_description, entry.from));
            enabled.setOnCheckedChangeListener((button, checked) ->
                    listener.onEnabledChanged(entry.id, checked));

            edit.setContentDescription(itemView.getContext().getString(
                    R.string.replacement_edit_content_description, entry.from));
            delete.setContentDescription(itemView.getContext().getString(
                    R.string.replacement_delete_content_description, entry.from));
            moveUp.setContentDescription(itemView.getContext().getString(
                    R.string.replacement_move_up_content_description, entry.from));
            moveDown.setContentDescription(itemView.getContext().getString(
                    R.string.replacement_move_down_content_description, entry.from));

            moveUp.setEnabled(position > 0);
            moveUp.setAlpha(position > 0 ? 1.0f : 0.35f);
            moveDown.setEnabled(position < count - 1);
            moveDown.setAlpha(position < count - 1 ? 1.0f : 0.35f);

            edit.setOnClickListener(view -> listener.onEdit(entry.id));
            moveUp.setOnClickListener(view -> listener.onMove(entry.id, -1));
            moveDown.setOnClickListener(view -> listener.onMove(entry.id, 1));
            delete.setOnClickListener(view -> listener.onDelete(entry.id));
            itemView.setOnClickListener(view -> listener.onEdit(entry.id));
        }
    }
}
