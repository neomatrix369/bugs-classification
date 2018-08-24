package org.ml_methods_group.core.changes;

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
import org.ml_methods_group.core.entities.Solution;

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
                .map(action -> fromAction(action, before.getSolutionId(), after.getSolutionId()))
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

    private CodeChange fromAction(Action action, int originalId, int targetId) {
        if (action.getClass() == Insert.class) {
            return fromAction((Insert) action, originalId, targetId);
        } else if (action.getClass() == Delete.class) {
            return fromAction((Delete) action, originalId, targetId);
        } else if (action.getClass() == Move.class) {
            return fromAction((Move) action, originalId, targetId);
        } else {
            return fromAction((Update) action, originalId, targetId);
        }
    }

    private CodeChange fromAction(Insert insert, int originalId, int targetId) {
        final ITree node = insert.getNode();
        return CodeChange.createInsertChange(
                originalId,
                targetId,
                getNodeType(node, 0),
                getNodeType(node, 1),
                getNodeType(node, 2),
                node.getLabel()
        );
    }

    private CodeChange fromAction(Delete delete, int originalId, int targetId) {
        final ITree node = delete.getNode();
        return CodeChange.createDeleteChange(
                originalId,
                targetId,
                getNodeType(node, 0),
                getNodeType(node, 1),
                getNodeType(node, 2),
                node.getLabel()
        );
    }


    private CodeChange fromAction(Move move, int originalId, int targetId) {
        final ITree node = move.getNode();
        final ITree parent = move.getParent();
        return CodeChange.createMoveChange(
                originalId,
                targetId,
                getNodeType(node, 0),
                getNodeType(parent, 0),
                getNodeType(parent, 1),
                getNodeType(node, 1),
                getNodeType(node, 2),
                node.getLabel()
        );
    }

    private CodeChange fromAction(Update update, int originalId, int targetId) {
        final ITree node = update.getNode();
        return CodeChange.createUpdateChange(
                originalId,
                targetId,
                getNodeType(node, 0),
                getNodeType(node, 1),
                getNodeType(node, 2),
                update.getValue(),
                node.getLabel()
        );
    }

    private static NodeType getNodeType(ITree node, int steps) {
        if (node == null) {
            return NodeType.NONE;
        }
        if (steps == 0) {
            return node.getType() < 0 ? NodeType.NONE : NodeType.valueOf(node.getType());
        }
        return getNodeType(node.getParent(), steps - 1);
    }
}
