package com.whisperonnx;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.whisperonnx.asr.WordReplacementEditorModel;
import com.whisperonnx.asr.WordReplacements;
import com.whisperonnx.utils.ThemeUtils;
import com.whisperonnx.utils.WordReplacementAdapter;

public class WordReplacementActivity extends AppCompatActivity {
    private SharedPreferences preferences;
    private WordReplacementEditorModel model;
    private WordReplacementAdapter adapter;
    private TextView emptyState;
    private RecyclerView replacementList;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_word_replacements);
        ThemeUtils.setStatusBarAppearance(this);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) actionBar.setDisplayHomeAsUpEnabled(true);
        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        model = new WordReplacementEditorModel(WordReplacements.load(preferences));
        emptyState = findViewById(R.id.replacement_empty_state);
        replacementList = findViewById(R.id.replacement_list);
        replacementList.setLayoutManager(new LinearLayoutManager(this));
        adapter = new WordReplacementAdapter(new ReplacementListener());
        replacementList.setAdapter(adapter);
        Button add = findViewById(R.id.btn_add_replacement);
        add.setOnClickListener(view -> showEditor(null));
        refresh();
    }

    private final class ReplacementListener implements WordReplacementAdapter.Listener {
        @Override public void onEdit(String id) { showEditor(model.find(id)); }
        @Override public void onEnabledChanged(String id, boolean enabled) {
            if (model.setEnabled(id, enabled)) persistAndRefresh();
        }
        @Override public void onMove(String id, int delta) {
            if (model.move(id, delta)) persistAndRefresh();
        }
        @Override public void onDelete(String id) {
            if (model.delete(id)) persistAndRefresh();
        }
    }

    private void showEditor(WordReplacements.Entry existing) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_add_replacement, null);
        EditText from = dialogView.findViewById(R.id.et_from);
        EditText to = dialogView.findViewById(R.id.et_to);
        if (existing != null) {
            from.setText(existing.from);
            to.setText(existing.to);
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(existing == null ? R.string.replacement_add_title
                        : R.string.replacement_edit_title)
                .setView(dialogView)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(button -> {
                    String source = from.getText().toString().trim();
                    String replacement = to.getText().toString();
                    WordReplacements.ValidationResult result = existing == null
                            ? model.add(source, replacement)
                            : model.update(existing.id, source, replacement);
                    if (!result.isValid()) {
                        from.setError(result.getMessage());
                        return;
                    }
                    persistAndRefresh();
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private void persistAndRefresh() {
        WordReplacements.save(preferences, model.snapshot());
        refresh();
    }

    private void refresh() {
        java.util.List<WordReplacements.Entry> entries = model.snapshot();
        adapter.submitList(entries);
        emptyState.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        replacementList.setVisibility(entries.isEmpty() ? View.GONE : View.VISIBLE);
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
