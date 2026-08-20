package com.whisperonnx.asr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public class WordReplacementEditorModelTest {
    @Test public void editDisableReorderAndDeletePreserveStableIdentity() {
        WordReplacements.Entry first = new WordReplacements.Entry("one", "1");
        WordReplacements.Entry second = new WordReplacements.Entry("two", "2");
        WordReplacementEditorModel model = new WordReplacementEditorModel(Arrays.asList(first, second));
        assertTrue(model.update(first.id, "ONE", "first").isValid());
        assertTrue(model.setEnabled(first.id, false));
        assertTrue(model.move(second.id, -1));
        assertEquals(second.id, model.snapshot().get(0).id);
        assertEquals(first.id, model.snapshot().get(1).id);
        assertEquals("first", model.find(first.id).to);
        assertFalse(model.find(first.id).enabled);
        assertTrue(model.delete(second.id));
        assertEquals(1, model.snapshot().size());
    }

    @Test public void conflictsAreRejectedWithoutMutation() {
        WordReplacementEditorModel model = new WordReplacementEditorModel(
                Arrays.asList(new WordReplacements.Entry("one", "1")));
        assertFalse(model.add("ONE", "other").isValid());
        assertEquals(1, model.snapshot().size());
    }
}
