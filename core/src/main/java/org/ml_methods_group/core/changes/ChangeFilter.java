package org.ml_methods_group.core.changes;

import com.github.gumtreediff.actions.model.Delete;
import com.github.gumtreediff.actions.model.Insert;
import com.github.gumtreediff.actions.model.Move;
import com.github.gumtreediff.actions.model.Update;

public interface ChangeFilter {
    boolean accept(Update update);
    boolean accept(Insert insert);
    boolean accept(Move move);
    boolean accept(Delete delete);
}
