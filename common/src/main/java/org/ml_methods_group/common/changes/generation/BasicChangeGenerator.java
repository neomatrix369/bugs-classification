package org.ml_methods_group.common.changes.generation;

import com.github.gumtreediff.actions.ActionGenerator;
import com.github.gumtreediff.actions.model.*;
import com.github.gumtreediff.gen.TreeGenerator;
import com.github.gumtreediff.gen.jdt.JdtTreeGenerator;
import com.github.gumtreediff.matchers.CompositeMatchers.ClassicGumtree;
import com.github.gumtreediff.matchers.CompositeMatchers.CompleteGumtreeMatcher;
import com.github.gumtreediff.matchers.MappingStore;
import com.github.gumtreediff.matchers.Matcher;
import com.github.gumtreediff.tree.ITree;
import com.github.gumtreediff.tree.TreeContext;
import org.ml_methods_group.common.changes.CodeChange;
import org.ml_methods_group.common.changes.CodeChange.NodeState;
import org.ml_methods_group.common.Solution;
import org.ml_methods_group.common.changes.*;

import java.io.IOException;
import java.lang.ref.SoftReference;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

public class BasicChangeGenerator implements ChangeGenerator {
    private final TreeGenerator generator;
    private final Map<Solution, SoftReference<ITree>> cache = new ConcurrentHashMap<>();
    private final List<BiFunction<ITree, ITree, Matcher>> factories;
    private final ASTNormalizer astNormalizer;

    public BasicChangeGenerator(ASTNormalizer astNormalizer, List<BiFunction<ITree, ITree, Matcher>> factories) {
        this.astNormalizer = astNormalizer;
        this.factories = factories;
        this.generator = new JdtTreeGenerator();
    }

    public BasicChangeGenerator(ASTNormalizer astNormalizer) {
        this(astNormalizer, Arrays.asList(
                (x, y) -> new CompleteGumtreeMatcher(x, y, new MappingStore()),
                (x, y) -> new ClassicGumtree(x, y, new MappingStore())
        ));
    }

    @Override
    public Changes getChanges(Solution before, Solution after) {
        final List<Action> actions = factories.stream()
                .map(factory -> generate(before, after, factory))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .min(Comparator.comparingInt(List::size))
                .orElseGet(Collections::emptyList);
        final List<CodeChange> changes = actions.stream()
                .map(action -> fromAction(action))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        return new Changes(before, after, changes);
    }

    private Optional<List<Action>> generate(Solution before, Solution after,
                                            BiFunction<ITree, ITree, Matcher> factory) {
        try {
            final ITree beforeTree = getTree(before);
            final ITree afterTree = getTree(after);
            final Matcher matcher = factory.apply(beforeTree, afterTree);
            matcher.match();
            final ActionGenerator generator = new ActionGenerator(beforeTree, afterTree, matcher.getMappings());
            return Optional.of(generator.generate());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public ITree getTree(Solution solution) {
        final SoftReference<ITree> reference = cache.get(solution);
        final ITree cached = reference == null ? null : reference.get();
        if (cached != null) {
            return cached.deepCopy();
        }
        try {
            final String code = solution.getCode();
            final TreeContext context = generator.generateFromString(code);
            astNormalizer.normalize(context, code);
            final ITree tree = context.getRoot();
            cache.put(solution, new SoftReference<>(tree));
            return tree.deepCopy();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private CodeChange fromAction(Action action) {
        if (action.getClass() == Insert.class) {
            return fromAction((Insert) action);
        } else if (action.getClass() == Delete.class) {
            return fromAction((Delete) action);
        } else if (action.getClass() == Move.class) {
            return fromAction((Move) action);
        } else {
            return fromAction((Update) action);
        }
    }

    private InsertChange fromAction(Insert insert) {
        final ITree node = insert.getNode();
        return new InsertChange(
                getNodeState(node, 0),
                getNodeState(node, 1),
                getNodeState(node, 2),
                getChildrenStates(node),
                getChildrenStates(node.getParent())
        );
    }

    private DeleteChange fromAction(Delete delete) {
        final ITree node = delete.getNode();
        return new DeleteChange(
                getNodeState(node, 0),
                getNodeState(node, 1),
                getNodeState(node, 2),
                getChildrenStates(node),
                getChildrenStates(node.getParent())
        );
    }


    private MoveChange fromAction(Move move) {
        final ITree node = move.getNode();
        final ITree parent = move.getParent();
        return new MoveChange(
                getNodeState(node, 0),
                getNodeState(parent, 0),
                getNodeState(node, 1),
                getNodeState(parent, 1),
                getNodeState(node, 2),
                getChildrenStates(node),
                getChildrenStates(parent),
                getChildrenStates(node.getParent())
        );
    }

    private UpdateChange fromAction(Update update) {
        final ITree node = update.getNode();
        return new UpdateChange(
                new NodeState(NodeType.valueOf(node.getType()), update.getValue()),
                getNodeState(node, 0).getLabel(),
                getNodeState(node, 1),
                getNodeState(node, 2),
                getChildrenStates(node),
                getChildrenStates(node.getParent())
        );
    }

    private static NodeState getNodeState(ITree node, int steps) {
        if (node == null) {
            return CodeChange.NONE_STATE;
        }
        if (steps == 0) {
            final NodeType type = node.getType() < 0 ? NodeType.NONE : NodeType.valueOf(node.getType());
            final String label = node.getLabel().isEmpty() ? CodeChange.NO_LABEL : node.getLabel();
            return new NodeState(type, label);
        }
        return getNodeState(node.getParent(), steps - 1);
    }

    private static NodeState[] getChildrenStates(ITree node) {
        if (node == null || node.getType() == NodeType.BLOCK.ordinal()) {
            return CodeChange.EMPTY_STATE_ARRAY;
        }
        final List<ITree> list = node.getChildren();
        if (list.isEmpty()) {
            return CodeChange.EMPTY_STATE_ARRAY;
        }
        final NodeState[] result = new NodeState[list.size()];
        for (int i = 0; i < result.length; i++) {
            final ITree child = list.get(i);
            final NodeType type = child.getType() < 0 ? NodeType.NONE : NodeType.valueOf(child.getType());
            final String label = child.getLabel().isEmpty() ? CodeChange.NO_LABEL : child.getLabel();
            result[i] = new NodeState(type, label);
        }
        return result;
    }
}