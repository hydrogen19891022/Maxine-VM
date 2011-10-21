/*
 * Copyright (c) 2009, 2011, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.max.graal.compiler.phases;

import static com.sun.cri.bytecode.Bytecodes.*;
import static java.lang.reflect.Modifier.*;

import java.lang.reflect.*;
import java.util.*;

import com.oracle.max.criutils.*;
import com.oracle.max.graal.compiler.*;
import com.oracle.max.graal.compiler.graph.*;
import com.oracle.max.graal.compiler.graph.BlockMap.Block;
import com.oracle.max.graal.compiler.graph.BlockMap.DeoptBlock;
import com.oracle.max.graal.compiler.graph.BlockMap.ExceptionBlock;
import com.oracle.max.graal.compiler.schedule.*;
import com.oracle.max.graal.compiler.util.*;
import com.oracle.max.graal.compiler.util.LoopUtil.Loop;
import com.oracle.max.graal.compiler.value.*;
import com.oracle.max.graal.graph.*;
import com.oracle.max.graal.nodes.*;
import com.oracle.max.graal.nodes.DeoptimizeNode.DeoptAction;
import com.oracle.max.graal.nodes.PhiNode.PhiType;
import com.oracle.max.graal.nodes.calc.*;
import com.oracle.max.graal.nodes.extended.*;
import com.oracle.max.graal.nodes.java.*;
import com.oracle.max.graal.nodes.spi.*;
import com.sun.cri.bytecode.*;
import com.sun.cri.ci.*;
import com.sun.cri.ri.*;
import com.sun.cri.ri.RiType.Representation;

/**
 * The {@code GraphBuilder} class parses the bytecode of a method and builds the IR graph.
 */
public final class GraphBuilderPhase extends Phase {

    /**
     * The minimum value to which {@link GraalOptions#TraceBytecodeParserLevel} must be set to trace
     * the bytecode instructions as they are parsed.
     */
    public static final int TRACELEVEL_INSTRUCTIONS = 1;

    /**
     * The minimum value to which {@link GraalOptions#TraceBytecodeParserLevel} must be set to trace
     * the frame state before each bytecode instruction as it is parsed.
     */
    public static final int TRACELEVEL_STATE = 2;

    private Graph<EntryPointNode> graph;

    private final CiStatistics stats;
    private final RiRuntime runtime;
    private final RiResolvedMethod method;
    private final RiConstantPool constantPool;
    private RiExceptionHandler[] exceptionHandlers;

    private final BytecodeStream stream;           // the bytecode stream
    private final LogStream log;

    private FrameStateBuilder frameState;          // the current execution state
    private Block currentBlock;

    private int nextBlockNumber;

    private ValueNode methodSynchronizedObject;
    private CiExceptionHandler unwindHandler;

    private ExceptionBlock unwindBlock;
    private Block returnBlock;

    private boolean storeResultGraph;

    private final boolean useBranchPrediction;
    private final boolean eagerResolving;

    // the worklist of blocks, sorted by depth first number
    private final PriorityQueue<Block> workList = new PriorityQueue<Block>(10, new Comparator<Block>() {
        public int compare(Block o1, Block o2) {
            return o1.blockID - o2.blockID;
        }
    });

    private FixedWithNextNode lastInstr;                 // the last instruction added

    private final Set<Block> blocksOnWorklist = new HashSet<Block>();
    private final Set<Block> blocksVisited = new HashSet<Block>();

    private BitSet canTrap;

    public static final Map<RiMethod, Graph<EntryPointNode>> cachedGraphs = new WeakHashMap<RiMethod, Graph<EntryPointNode>>();


    public GraphBuilderPhase(GraalContext context, RiRuntime runtime, RiResolvedMethod method) {
        this(context, runtime, method, null);
    }

    public GraphBuilderPhase(GraalContext context, RiRuntime runtime, RiResolvedMethod method, CiStatistics stats) {
        this(context, runtime, method, stats, GraalOptions.UseBranchPrediction, false);
    }

    public GraphBuilderPhase(GraalContext context, RiRuntime runtime, RiResolvedMethod method, CiStatistics stats, boolean useBranchPrediction, boolean eagerResolving) {
        super(context);

        this.useBranchPrediction = useBranchPrediction;
        this.eagerResolving = eagerResolving;
        this.runtime = runtime;
        this.method = method;
        this.stats = stats;
        this.log = GraalOptions.TraceBytecodeParserLevel > 0 ? new LogStream(TTY.out()) : null;
        this.stream = new BytecodeStream(method.code());

        this.constantPool = runtime.getConstantPool(method);
        this.storeResultGraph = GraalOptions.CacheGraphs;
    }

    @Override
    protected void run(Graph<EntryPointNode> graph) {
        assert graph != null;
        this.graph = graph;
        this.frameState = new FrameStateBuilder(method, method.maxLocals(), method.maxStackSize(), graph);
        build();
    }

    @Override
    protected String getDetailedName() {
        return getName() + " " + CiUtil.format("%H.%n(%p):%r", method);
    }

    private BlockMap createBlockMap() {
        BlockMap map = new BlockMap(method, useBranchPrediction);
        map.build();
        if (stats != null) {
            stats.bytecodeCount += method.code().length;
        }
        return map;
    }

    private void build() {
        if (log != null) {
            log.println();
            log.println("Compiling " + method);
        }

        // compute the block map, setup exception handlers and get the entrypoint(s)
        BlockMap blockMap = createBlockMap();
        this.canTrap = blockMap.canTrap;

        exceptionHandlers = blockMap.exceptionHandlers();
        if (stats != null) {
            stats.blockCount += blockMap.blocks.size();
        }
        nextBlockNumber = blockMap.blocks.size();

        // create the start block
        Block startBlock = nextBlock(FixedWithNextNode.SYNCHRONIZATION_ENTRY_BCI);
        markOnWorkList(startBlock);
        lastInstr = (FixedWithNextNode) createTarget(startBlock, frameState);
        graph.start().setNext(lastInstr);

        if (isSynchronized(method.accessFlags())) {
            graph.start().setStateAfter(frameState.create(FixedWithNextNode.SYNCHRONIZATION_ENTRY_BCI));
            // add a monitor enter to the start block
            methodSynchronizedObject = synchronizedObject(frameState, method);
            genMonitorEnter(methodSynchronizedObject, FixedWithNextNode.SYNCHRONIZATION_ENTRY_BCI);
        } else {
            graph.start().setStateAfter(frameState.create(0));
        }

        // finish the start block
        appendGoto(createTarget(blockMap.startBlock, frameState));
        unwindHandler = new CiExceptionHandler(0, method.code().length, FixedWithNextNode.SYNCHRONIZATION_ENTRY_BCI, 0, null);

        // do the normal parsing
        iterateAllBlocks();

        List<Loop> loops = LoopUtil.computeLoops(graph);
        NodeBitMap loopExits = graph.createNodeBitMap();
        for (Loop loop : loops) {
            loopExits.setUnion(loop.exits());
        }

        // remove Placeholders (except for loop exits)
        for (PlaceholderNode n : graph.getNodes(PlaceholderNode.class)) {
            if (!loopExits.isMarked(n)) {
                n.replaceAndDelete(n.next());
            }
        }

        // remove dead FrameStates
        for (Node n : graph.getNodes(FrameState.class)) {
            if (n.usages().size() == 0 && n.predecessor() == null) {
                n.delete();
            }
        }

        if (storeResultGraph) {
            // Create duplicate graph.
            Graph<EntryPointNode> duplicate = new Graph<EntryPointNode>(new EntryPointNode(null));
            Map<Node, Node> replacements = new IdentityHashMap<Node, Node>();
            replacements.put(graph.start(), duplicate.start());
            duplicate.addDuplicate(graph.getNodes(), replacements);

            cachedGraphs.put(method, duplicate);
        }
    }

    private int nextBlockNumber() {
        if (stats != null) {
            stats.blockCount++;
        }
        return nextBlockNumber++;
    }

    private Block nextBlock(int bci) {
        Block block = new Block();
        block.startBci = bci;
        block.endBci = bci;
        block.blockID = nextBlockNumber();
        return block;
    }

    private Block unwindBlock(int bci) {
        if (unwindBlock == null) {
            unwindBlock = new ExceptionBlock();
            unwindBlock.startBci = -1;
            unwindBlock.endBci = -1;
            unwindBlock.deoptBci = bci;
            unwindBlock.blockID = nextBlockNumber();
            addToWorkList(unwindBlock);
        }
        return unwindBlock;
    }

    private Block returnBlock(int bci) {
        if (returnBlock == null) {
            returnBlock = new Block();
            returnBlock.startBci = bci;
            returnBlock.endBci = bci;
            returnBlock.blockID = nextBlockNumber();
            addToWorkList(returnBlock);
        }
        return returnBlock;
    }

    private void markOnWorkList(Block block) {
        blocksOnWorklist.add(block);
    }

    private boolean isOnWorkList(Block block) {
        return blocksOnWorklist.contains(block);
    }

    private void markVisited(Block block) {
        blocksVisited.add(block);
    }

    private boolean isVisited(Block block) {
        return blocksVisited.contains(block);
    }

    public void mergeOrClone(Block target, FrameStateAccess newState) {
        StateSplit first = (StateSplit) target.firstInstruction;

        if (target.isLoopHeader && isVisited(target)) {
            first = (StateSplit) loopBegin(target).loopEnd().predecessor();
        }

        int bci = target.startBci;
        if (target instanceof ExceptionBlock) {
            bci = ((ExceptionBlock) target).deoptBci;
        }

        FrameState existingState = first.stateAfter();

        if (existingState == null) {
            // copy state because it is modified
            first.setStateAfter(newState.duplicate(bci));
        } else {
            if (!GraalOptions.AssumeVerifiedBytecode && !existingState.isCompatibleWith(newState)) {
                // stacks or locks do not match--bytecodes would not verify
                TTY.println(existingState.toString());
                TTY.println(newState.duplicate(0).toString());
                throw new CiBailout("stack or locks do not match");
            }
            assert existingState.localsSize() == newState.localsSize();
            assert existingState.stackSize() == newState.stackSize();

            if (first instanceof PlaceholderNode) {
                PlaceholderNode p = (PlaceholderNode) first;
                if (p.predecessor() == null) {
                    p.setStateAfter(newState.duplicate(bci));
                    return;
                } else {
                    MergeNode merge = graph.add(new MergeNode());
                    FixedNode next = p.next();
                    EndNode end = graph.add(new EndNode());
                    p.setNext(end);
                    merge.setNext(next);
                    merge.addEnd(end);
                    merge.setStateAfter(existingState);
                    p.setStateAfter(existingState.duplicate(bci));
                    if (!(next instanceof LoopEndNode)) {
                        target.firstInstruction = merge;
                    }
                    first = merge;
                }
            }

            existingState.merge((MergeNode) first, newState);
        }
    }

    public BytecodeStream stream() {
        return stream;
    }

    public int bci() {
        return stream.currentBCI();
    }

    private void loadLocal(int index, CiKind kind) {
        frameState.push(kind, frameState.loadLocal(index));
    }

    private void storeLocal(CiKind kind, int index) {
        frameState.storeLocal(index, frameState.pop(kind));
    }

    public boolean covers(RiExceptionHandler handler, int bci) {
        return handler.startBCI() <= bci && bci < handler.endBCI();
    }

    public boolean isCatchAll(RiExceptionHandler handler) {
        return handler.catchTypeCPI() == 0;
    }

    private FixedNode handleException(ValueNode exceptionObject, int bci) {
        assert bci == FixedWithNextNode.SYNCHRONIZATION_ENTRY_BCI || bci == bci() : "invalid bci";

        if (GraalOptions.UseExceptionProbability && method.invocationCount() > GraalOptions.MatureInvocationCount) {
            if (bci != FixedWithNextNode.SYNCHRONIZATION_ENTRY_BCI && exceptionObject == null && method.exceptionProbability(bci) == 0) {
                return null;
            }
        }

        RiExceptionHandler firstHandler = null;
        // join with all potential exception handlers
        if (exceptionHandlers != null) {
            for (RiExceptionHandler handler : exceptionHandlers) {
                // if the handler covers this bytecode index, add it to the list
                if (covers(handler, bci)) {
                    firstHandler = handler;
                    break;
                }
            }
        }

        if (firstHandler == null) {
            firstHandler = unwindHandler;
        }

        if (firstHandler != null) {
            Block dispatchBlock = null;
            if (firstHandler.handlerBCI() == FixedWithNextNode.SYNCHRONIZATION_ENTRY_BCI) {
                dispatchBlock = unwindBlock(bci);
            } else {
                for (int i = currentBlock.normalSuccessors; i < currentBlock.successors.size(); i++) {
                    Block block = currentBlock.successors.get(i);
                    if (block instanceof ExceptionBlock && ((ExceptionBlock) block).handler == firstHandler) {
                        dispatchBlock = block;
                        break;
                    }
                    if (isCatchAll(firstHandler) && block.startBci == firstHandler.handlerBCI()) {
                        dispatchBlock = block;
                        break;
                    }
                }
            }

            PlaceholderNode p = graph.add(new PlaceholderNode());
            p.setStateAfter(frameState.duplicateWithoutStack(bci));

            ValueNode currentExceptionObject;
            ExceptionObjectNode newObj = null;
            if (exceptionObject == null) {
                newObj = graph.add(new ExceptionObjectNode());
                currentExceptionObject = newObj;
            } else {
                currentExceptionObject = exceptionObject;
            }
            FrameState stateWithException = frameState.duplicateWithException(bci, currentExceptionObject);
            if (newObj != null) {
                newObj.setStateAfter(stateWithException);
            }
            FixedNode target = createTarget(dispatchBlock, stateWithException);
            if (exceptionObject == null) {
                ExceptionObjectNode eObj = (ExceptionObjectNode) currentExceptionObject;
                eObj.setNext(target);
                p.setNext(eObj);
            } else {
                p.setNext(target);
            }
            return p;
        }
        return null;
    }

    private void genLoadConstant(int cpi) {
        Object con = constantPool.lookupConstant(cpi);

        if (con instanceof RiType) {
            // this is a load of class constant which might be unresolved
            RiType riType = (RiType) con;
            if (riType instanceof RiResolvedType) {
                frameState.push(CiKind.Object, append(graph.unique(new ConstantNode(((RiResolvedType) riType).getEncoding(Representation.JavaClass)))));
            } else {
                storeResultGraph = false;
                append(graph.add(new DeoptimizeNode(DeoptAction.InvalidateRecompile)));
                frameState.push(CiKind.Object, append(ConstantNode.forObject(null, graph)));
            }
        } else if (con instanceof CiConstant) {
            CiConstant constant = (CiConstant) con;
            frameState.push(constant.kind.stackKind(), appendConstant(constant));
        } else {
            throw new Error("lookupConstant returned an object of incorrect type");
        }
    }

    private void genLoadIndexed(CiKind kind) {
        emitExplicitExceptions(frameState.peek(1), frameState.peek(0));

        ValueNode index = frameState.ipop();
        ValueNode array = frameState.apop();
        ValueNode length = append(graph.add(new ArrayLengthNode(array)));
        ValueNode v = append(graph.add(new LoadIndexedNode(array, index, length, kind)));
        frameState.push(kind.stackKind(), v);
    }

    private void genStoreIndexed(CiKind kind) {
        emitExplicitExceptions(frameState.peek(2), frameState.peek(1));

        ValueNode value = frameState.pop(kind.stackKind());
        ValueNode index = frameState.ipop();
        ValueNode array = frameState.apop();
        ValueNode length = append(graph.add(new ArrayLengthNode(array)));
        StoreIndexedNode result = graph.add(new StoreIndexedNode(array, index, length, kind, value));
        append(result);
    }

    private void stackOp(int opcode) {
        switch (opcode) {
            case POP: {
                frameState.xpop();
                break;
            }
            case POP2: {
                frameState.xpop();
                frameState.xpop();
                break;
            }
            case DUP: {
                ValueNode w = frameState.xpop();
                frameState.xpush(w);
                frameState.xpush(w);
                break;
            }
            case DUP_X1: {
                ValueNode w1 = frameState.xpop();
                ValueNode w2 = frameState.xpop();
                frameState.xpush(w1);
                frameState.xpush(w2);
                frameState.xpush(w1);
                break;
            }
            case DUP_X2: {
                ValueNode w1 = frameState.xpop();
                ValueNode w2 = frameState.xpop();
                ValueNode w3 = frameState.xpop();
                frameState.xpush(w1);
                frameState.xpush(w3);
                frameState.xpush(w2);
                frameState.xpush(w1);
                break;
            }
            case DUP2: {
                ValueNode w1 = frameState.xpop();
                ValueNode w2 = frameState.xpop();
                frameState.xpush(w2);
                frameState.xpush(w1);
                frameState.xpush(w2);
                frameState.xpush(w1);
                break;
            }
            case DUP2_X1: {
                ValueNode w1 = frameState.xpop();
                ValueNode w2 = frameState.xpop();
                ValueNode w3 = frameState.xpop();
                frameState.xpush(w2);
                frameState.xpush(w1);
                frameState.xpush(w3);
                frameState.xpush(w2);
                frameState.xpush(w1);
                break;
            }
            case DUP2_X2: {
                ValueNode w1 = frameState.xpop();
                ValueNode w2 = frameState.xpop();
                ValueNode w3 = frameState.xpop();
                ValueNode w4 = frameState.xpop();
                frameState.xpush(w2);
                frameState.xpush(w1);
                frameState.xpush(w4);
                frameState.xpush(w3);
                frameState.xpush(w2);
                frameState.xpush(w1);
                break;
            }
            case SWAP: {
                ValueNode w1 = frameState.xpop();
                ValueNode w2 = frameState.xpop();
                frameState.xpush(w1);
                frameState.xpush(w2);
                break;
            }
            default:
                throw Util.shouldNotReachHere();
        }

    }

    private void genArithmeticOp(CiKind result, int opcode, boolean canTrap) {
        ValueNode y = frameState.pop(result);
        ValueNode x = frameState.pop(result);
        boolean isStrictFP = isStrict(method.accessFlags());
        ArithmeticNode v;
        switch(opcode){
            case IADD:
            case LADD: v = new IntegerAddNode(result, x, y); break;
            case FADD:
            case DADD: v = new FloatAddNode(result, x, y, isStrictFP); break;
            case ISUB:
            case LSUB: v = new IntegerSubNode(result, x, y); break;
            case FSUB:
            case DSUB: v = new FloatSubNode(result, x, y, isStrictFP); break;
            case IMUL:
            case LMUL: v = new IntegerMulNode(result, x, y); break;
            case FMUL:
            case DMUL: v = new FloatMulNode(result, x, y, isStrictFP); break;
            case IDIV:
            case LDIV: v = new IntegerDivNode(result, x, y); break;
            case FDIV:
            case DDIV: v = new FloatDivNode(result, x, y, isStrictFP); break;
            case IREM:
            case LREM: v = new IntegerRemNode(result, x, y); break;
            case FREM:
            case DREM: v = new FloatRemNode(result, x, y, isStrictFP); break;
            default:
                throw new CiBailout("should not reach");
        }
        ValueNode result1 = append(graph.unique(v));
        if (canTrap) {
            append(graph.add(new ValueAnchorNode(result1)));
        }
        frameState.push(result, result1);
    }

    private void genNegateOp(CiKind kind) {
        frameState.push(kind, append(graph.unique(new NegateNode(frameState.pop(kind)))));
    }

    private void genShiftOp(CiKind kind, int opcode) {
        ValueNode s = frameState.ipop();
        ValueNode x = frameState.pop(kind);
        ShiftNode v;
        switch(opcode){
            case ISHL:
            case LSHL: v = new LeftShiftNode(kind, x, s); break;
            case ISHR:
            case LSHR: v = new RightShiftNode(kind, x, s); break;
            case IUSHR:
            case LUSHR: v = new UnsignedRightShiftNode(kind, x, s); break;
            default:
                throw new CiBailout("should not reach");
        }
        frameState.push(kind, append(graph.unique(v)));
    }

    private void genLogicOp(CiKind kind, int opcode) {
        ValueNode y = frameState.pop(kind);
        ValueNode x = frameState.pop(kind);
        LogicNode v;
        switch(opcode){
            case IAND:
            case LAND: v = new AndNode(kind, x, y); break;
            case IOR:
            case LOR: v = new OrNode(kind, x, y); break;
            case IXOR:
            case LXOR: v = new XorNode(kind, x, y); break;
            default:
                throw new CiBailout("should not reach");
        }
        frameState.push(kind, append(graph.unique(v)));
    }

    private void genCompareOp(CiKind kind, int opcode, CiKind resultKind) {
        ValueNode y = frameState.pop(kind);
        ValueNode x = frameState.pop(kind);
        ValueNode value = append(graph.unique(new NormalizeCompareNode(opcode, resultKind, x, y)));
        if (!resultKind.isVoid()) {
            frameState.ipush(value);
        }
    }

    private void genConvert(ConvertNode.Op opcode, CiKind from, CiKind to) {
        CiKind tt = to.stackKind();
        frameState.push(tt, append(graph.unique(new ConvertNode(opcode, frameState.pop(from.stackKind()), tt))));
    }

    private void genIncrement() {
        int index = stream().readLocalIndex();
        int delta = stream().readIncrement();
        ValueNode x = frameState.localAt(index);
        ValueNode y = append(ConstantNode.forInt(delta, graph));
        frameState.storeLocal(index, append(graph.unique(new IntegerAddNode(CiKind.Int, x, y))));
    }

    private void genGoto(int toBCI) {
        appendGoto(createTarget(currentBlock.successors.get(0), frameState));
        assert currentBlock.normalSuccessors == 1;
    }

    private void ifNode(ValueNode x, Condition cond, ValueNode y) {
        assert !x.isDeleted() && !y.isDeleted();
        double probability = method.branchProbability(bci());
        if (probability < 0) {
            if (GraalOptions.TraceProbability) {
                TTY.println("missing probability in " + method + " at bci " + bci());
            }
            probability = 0.5;
        }

        IfNode ifNode = graph.add(new IfNode(graph.unique(new CompareNode(x, cond, y)), probability));
        append(ifNode);
        ifNode.setTrueSuccessor(BeginNode.begin(createTarget(currentBlock.successors.get(0), frameState)));
        ifNode.setFalseSuccessor(BeginNode.begin(createTarget(currentBlock.successors.get(1), frameState)));

        assert currentBlock.normalSuccessors == 2 : currentBlock.normalSuccessors;
    }

    private void genIfZero(Condition cond) {
        ValueNode y = appendConstant(CiConstant.INT_0);
        ValueNode x = frameState.ipop();
        ifNode(x, cond, y);
    }

    private void genIfNull(Condition cond) {
        ValueNode y = appendConstant(CiConstant.NULL_OBJECT);
        ValueNode x = frameState.apop();
        ifNode(x, cond, y);
    }

    private void genIfSame(CiKind kind, Condition cond) {
        ValueNode y = frameState.pop(kind);
        ValueNode x = frameState.pop(kind);
        assert !x.isDeleted() && !y.isDeleted();
        ifNode(x, cond, y);
    }

    private void genThrow(int bci) {
        ValueNode exception = frameState.apop();
        FixedGuardNode node = graph.add(new FixedGuardNode(graph.unique(new IsNonNullNode(exception))));
        append(node);

        FixedNode entry = handleException(exception, bci);
        if (entry != null) {
            append(entry);
        } else {
            appendGoto(createTarget(unwindBlock(bci), frameState.duplicateWithException(bci, exception)));
        }
    }

    private RiType lookupType(int cpi, int bytecode) {
        eagerResolving(cpi, bytecode);
        RiType result = constantPool.lookupType(cpi, bytecode);
        assert !eagerResolving || result instanceof RiResolvedType;
        return result;
    }

    private RiMethod lookupMethod(int cpi, int opcode) {
        eagerResolving(cpi, opcode);
        RiMethod result = constantPool.lookupMethod(cpi, opcode);
        assert !eagerResolving || ((result instanceof RiResolvedMethod) && ((RiResolvedMethod) result).holder().isInitialized());
        return result;
    }

    private RiField lookupField(int cpi, int opcode) {
        eagerResolving(cpi, opcode);
        RiField result = constantPool.lookupField(cpi, opcode);
        assert !eagerResolving || (result instanceof RiResolvedField && ((RiResolvedField) result).holder().isInitialized());
        return result;
    }

    private void eagerResolving(int cpi, int bytecode) {
        if (eagerResolving) {
            constantPool.loadReferencedType(cpi, bytecode);
        }
    }

    private void genCheckCast() {
        int cpi = stream().readCPI();
        RiType type = lookupType(cpi, CHECKCAST);
        boolean initialized = type instanceof RiResolvedType;
        if (initialized) {
            ConstantNode typeInstruction = genTypeOrDeopt(RiType.Representation.ObjectHub, type, true);
            ValueNode object = frameState.apop();
            AnchorNode anchor = graph.add(new AnchorNode());
            append(anchor);
            CheckCastNode checkCast = graph.unique(new CheckCastNode(anchor, typeInstruction, object));
            append(graph.add(new ValueAnchorNode(checkCast)));
            frameState.apush(checkCast);
        } else {
            ValueNode object = frameState.apop();
            append(graph.add(new FixedGuardNode(graph.unique(new CompareNode(object, Condition.EQ, graph.unique(new ConstantNode(CiConstant.NULL_OBJECT)))))));
            frameState.apush(appendConstant(CiConstant.NULL_OBJECT));
        }
    }

    private void genInstanceOf() {
        int cpi = stream().readCPI();
        RiType type = lookupType(cpi, INSTANCEOF);
        ConstantNode typeInstruction = genTypeOrDeopt(RiType.Representation.ObjectHub, type, type instanceof RiResolvedType);
        ValueNode object = frameState.apop();
        if (typeInstruction != null) {
            frameState.ipush(append(MaterializeNode.create(graph.unique(new InstanceOfNode(typeInstruction, object)), graph)));
        } else {
            frameState.ipush(appendConstant(CiConstant.INT_0));
        }
    }

    void genNewInstance(int cpi) {
        RiType type = lookupType(cpi, NEW);
        if (type instanceof RiResolvedType) {
            NewInstanceNode n = graph.add(new NewInstanceNode((RiResolvedType) type));
            frameState.apush(append(n));
        } else {
            storeResultGraph = false;
            append(graph.add(new DeoptimizeNode(DeoptAction.InvalidateRecompile)));
            frameState.apush(appendConstant(CiConstant.NULL_OBJECT));
        }
    }

    /**
     * Gets the kind of array elements for the array type code that appears
     * in a {@link Bytecodes#NEWARRAY} bytecode.
     * @param code the array type code
     * @return the kind from the array type code
     */
    public static CiKind arrayTypeCodeToKind(int code) {
        // Checkstyle: stop
        switch (code) {
            case 4:  return CiKind.Boolean;
            case 5:  return CiKind.Char;
            case 6:  return CiKind.Float;
            case 7:  return CiKind.Double;
            case 8:  return CiKind.Byte;
            case 9:  return CiKind.Short;
            case 10: return CiKind.Int;
            case 11: return CiKind.Long;
            default: throw new IllegalArgumentException("unknown array type code: " + code);
        }
        // Checkstyle: resume
    }

    private void genNewTypeArray(int typeCode) {
        CiKind kind = arrayTypeCodeToKind(typeCode);
        RiResolvedType elementType = runtime.asRiType(kind);
        NewTypeArrayNode nta = graph.add(new NewTypeArrayNode(frameState.ipop(), elementType));
        frameState.apush(append(nta));
    }

    private void genNewObjectArray(int cpi) {
        RiType type = lookupType(cpi, ANEWARRAY);
        ValueNode length = frameState.ipop();
        if (type instanceof RiResolvedType) {
            NewArrayNode n = graph.add(new NewObjectArrayNode((RiResolvedType) type, length));
            frameState.apush(append(n));
        } else {
            storeResultGraph = false;
            append(graph.add(new DeoptimizeNode(DeoptAction.InvalidateRecompile)));
            frameState.apush(appendConstant(CiConstant.NULL_OBJECT));
        }

    }

    private void genNewMultiArray(int cpi) {
        RiType type = lookupType(cpi, MULTIANEWARRAY);
        int rank = stream().readUByte(bci() + 3);
        ValueNode[] dims = new ValueNode[rank];
        for (int i = rank - 1; i >= 0; i--) {
            dims[i] = frameState.ipop();
        }
        if (type instanceof RiResolvedType) {
            NewArrayNode n = graph.add(new NewMultiArrayNode((RiResolvedType) type, dims, cpi, constantPool));
            frameState.apush(append(n));
        } else {
            storeResultGraph = false;
            append(graph.add(new DeoptimizeNode(DeoptAction.InvalidateRecompile)));
            frameState.apush(appendConstant(CiConstant.NULL_OBJECT));
        }
    }

    private void genGetField(int cpi, RiField field) {
        emitExplicitExceptions(frameState.peek(0), null);

        CiKind kind = field.kind(false);
        ValueNode receiver = frameState.apop();
        if ((field instanceof RiResolvedField) && ((RiResolvedField) field).holder().isInitialized()) {
            LoadFieldNode load = graph.add(new LoadFieldNode(receiver, (RiResolvedField) field));
            appendOptimizedLoadField(kind, load);
        } else {
            storeResultGraph = false;
            append(graph.add(new DeoptimizeNode(DeoptAction.InvalidateRecompile)));
            frameState.push(kind.stackKind(), append(ConstantNode.defaultForKind(kind, graph)));
        }
    }

    public static class ExceptionInfo {

        public final FixedWithNextNode exceptionEdge;
        public final ValueNode exception;

        public ExceptionInfo(FixedWithNextNode exceptionEdge, ValueNode exception) {
            this.exceptionEdge = exceptionEdge;
            this.exception = exception;
        }
    }

    private ExceptionInfo emitNullCheck(ValueNode receiver) {
        PlaceholderNode trueSucc = graph.add(new PlaceholderNode());
        PlaceholderNode falseSucc = graph.add(new PlaceholderNode());
        IfNode ifNode = graph.add(new IfNode(graph.unique(new IsNonNullNode(receiver)), trueSucc, falseSucc, 1));

        append(ifNode);
        lastInstr = trueSucc;

        if (GraalOptions.OmitHotExceptionStacktrace) {
            ValueNode exception = ConstantNode.forObject(new NullPointerException(), graph);
            return new ExceptionInfo(falseSucc, exception);
        } else {
            RuntimeCallNode call = graph.add(new RuntimeCallNode(CiRuntimeCall.CreateNullPointerException));
            call.setStateAfter(frameState.duplicate(bci()));
            falseSucc.setNext(call);
            return new ExceptionInfo(call, call);
        }
    }

    private ExceptionInfo emitBoundsCheck(ValueNode index, ValueNode length) {
        PlaceholderNode trueSucc = graph.add(new PlaceholderNode());
        PlaceholderNode falseSucc = graph.add(new PlaceholderNode());
        IfNode ifNode = graph.add(new IfNode(graph.unique(new CompareNode(index, Condition.BT, length)), trueSucc, falseSucc, 1));

        append(ifNode);
        lastInstr = trueSucc;

        if (GraalOptions.OmitHotExceptionStacktrace) {
            ValueNode exception = ConstantNode.forObject(new ArrayIndexOutOfBoundsException(), graph);
            return new ExceptionInfo(falseSucc, exception);
        } else {
            RuntimeCallNode call = graph.add(new RuntimeCallNode(CiRuntimeCall.CreateOutOfBoundsException, new ValueNode[] {index}));
            call.setStateAfter(frameState.duplicate(bci()));
            falseSucc.setNext(call);
            return new ExceptionInfo(call, call);
        }
    }

    private void emitExplicitExceptions(ValueNode receiver, ValueNode outOfBoundsIndex) {
        assert receiver != null;

        if (canTrap.get(bci()) && GraalOptions.AllowExplicitExceptionChecks) {
            ArrayList<ExceptionInfo> exceptions = new ArrayList<ExceptionInfo>(2);
            exceptions.add(emitNullCheck(receiver));
            if (outOfBoundsIndex != null) {
                ArrayLengthNode length = graph.add(new ArrayLengthNode(receiver));
                append(length);
                exceptions.add(emitBoundsCheck(outOfBoundsIndex, length));
            }
            final ExceptionInfo exception;
            if (exceptions.size() == 1) {
                exception = exceptions.get(0);
            } else {
                assert exceptions.size() > 1;
                MergeNode merge = graph.add(new MergeNode());
                PhiNode phi = graph.unique(new PhiNode(CiKind.Object, merge, PhiType.Value));
                for (ExceptionInfo info : exceptions) {
                    EndNode end = graph.add(new EndNode());
                    info.exceptionEdge.setNext(end);
                    merge.addEnd(end);
                    phi.addInput(info.exception);
                }
                merge.setStateAfter(frameState.duplicate(bci()));
                exception = new ExceptionInfo(merge, phi);
            }

            FixedNode entry = handleException(exception.exception, bci());
            if (entry != null) {
                exception.exceptionEdge.setNext(entry);
            } else {
                exception.exceptionEdge.setNext(createTarget(unwindBlock(bci()), frameState.duplicateWithException(bci(), exception.exception)));
            }
            if (GraalOptions.Meter) {
                context.metrics.ExplicitExceptions++;
            }
        }
    }

    private void genPutField(int cpi, RiField field) {
        emitExplicitExceptions(frameState.peek(1), null);

        ValueNode value = frameState.pop(field.kind(false).stackKind());
        ValueNode receiver = frameState.apop();
        if (field instanceof RiResolvedField && ((RiResolvedField) field).holder().isInitialized()) {
            StoreFieldNode store = graph.add(new StoreFieldNode(receiver, (RiResolvedField) field, value));
            appendOptimizedStoreField(store);
        } else {
            storeResultGraph = false;
            append(graph.add(new DeoptimizeNode(DeoptAction.InvalidateRecompile)));
        }
    }

    private void genGetStatic(int cpi, RiField field) {
        RiType holder = field.holder();
        boolean isInitialized = (field instanceof RiResolvedField) && ((RiResolvedType) holder).isInitialized();
        CiConstant constantValue = null;
        if (isInitialized) {
            constantValue = ((RiResolvedField) field).constantValue(null);
        }
        if (constantValue != null) {
            frameState.push(constantValue.kind.stackKind(), appendConstant(constantValue));
        } else {
            ValueNode container = genTypeOrDeopt(RiType.Representation.StaticFields, holder, isInitialized);
            CiKind kind = field.kind(false);
            if (container != null) {
                LoadFieldNode load = graph.add(new LoadFieldNode(container, (RiResolvedField) field));
                appendOptimizedLoadField(kind, load);
            } else {
                // deopt will be generated by genTypeOrDeopt, not needed here
                frameState.push(kind.stackKind(), append(ConstantNode.defaultForKind(kind, graph)));
            }
        }
    }

    private void genPutStatic(int cpi, RiField field) {
        RiType holder = field.holder();
        ValueNode container = genTypeOrDeopt(RiType.Representation.StaticFields, holder, field instanceof RiResolvedField && ((RiResolvedType) holder).isInitialized());
        ValueNode value = frameState.pop(field.kind(false).stackKind());
        if (container != null) {
            StoreFieldNode store = graph.add(new StoreFieldNode(container, (RiResolvedField) field, value));
            appendOptimizedStoreField(store);
        } else {
            // deopt will be generated by genTypeOrDeopt, not needed here
        }
    }

    private ConstantNode genTypeOrDeopt(RiType.Representation representation, RiType holder, boolean initialized) {
        if (initialized) {
            return appendConstant(((RiResolvedType) holder).getEncoding(representation));
        } else {
            storeResultGraph = false;
            append(graph.add(new DeoptimizeNode(DeoptAction.InvalidateRecompile)));
            return null;
        }
    }

    private void appendOptimizedStoreField(StoreFieldNode store) {
        append(store);
    }

    private void appendOptimizedLoadField(CiKind kind, LoadFieldNode load) {
        // append the load to the instruction
        ValueNode optimized = append(load);
        frameState.push(kind.stackKind(), optimized);
    }

    private void genInvokeStatic(RiMethod target, int cpi, RiConstantPool constantPool) {
        if (target instanceof RiResolvedMethod) {
            RiResolvedMethod resolvedTarget = (RiResolvedMethod) target;
            RiResolvedType holder = resolvedTarget.holder();
            if (!holder.isInitialized() && GraalOptions.ResolveClassBeforeStaticInvoke) {
                // Re-use the same resolution code as for accessing a static field. Even though
                // the result of resolution is not used by the invocation (only the side effect
                // of initialization is required), it can be commoned with static field accesses.
                genTypeOrDeopt(RiType.Representation.StaticFields, holder, false);
            }
            ValueNode[] args = frameState.popArguments(resolvedTarget.signature().argumentSlots(false));
            appendInvoke(INVOKESTATIC, resolvedTarget, args, cpi, constantPool);
        } else {
            genInvokeDeopt(target, false);
        }
    }

    private void genInvokeInterface(RiMethod target, int cpi, RiConstantPool constantPool) {
        if (target instanceof RiResolvedMethod) {
            ValueNode[] args = frameState.popArguments(target.signature().argumentSlots(true));
            genInvokeIndirect(INVOKEINTERFACE, (RiResolvedMethod) target, args, cpi, constantPool);
        } else {
            genInvokeDeopt(target, true);
        }
    }

    private void genInvokeVirtual(RiMethod target, int cpi, RiConstantPool constantPool) {
        if (target instanceof RiResolvedMethod) {
            ValueNode[] args = frameState.popArguments(target.signature().argumentSlots(true));
            genInvokeIndirect(INVOKEVIRTUAL, (RiResolvedMethod) target, args, cpi, constantPool);
        } else {
            genInvokeDeopt(target, true);
        }

    }

    private void genInvokeSpecial(RiMethod target, RiType knownHolder, int cpi, RiConstantPool constantPool) {
        if (target instanceof RiResolvedMethod) {
            assert target != null;
            assert target.signature() != null;
            ValueNode[] args = frameState.popArguments(target.signature().argumentSlots(true));
            invokeDirect((RiResolvedMethod) target, args, knownHolder, cpi, constantPool);
        } else {
            genInvokeDeopt(target, true);
        }
    }

    private void genInvokeDeopt(RiMethod unresolvedTarget, boolean withReceiver) {
        storeResultGraph = false;
        append(graph.add(new DeoptimizeNode(DeoptAction.InvalidateRecompile)));
        frameState.popArguments(unresolvedTarget.signature().argumentSlots(withReceiver));
        CiKind kind = unresolvedTarget.signature().returnKind(false);
        if (kind != CiKind.Void) {
            frameState.push(kind.stackKind(), append(ConstantNode.defaultForKind(kind, graph)));
        }
    }

    private void genInvokeIndirect(int opcode, RiResolvedMethod target, ValueNode[] args, int cpi, RiConstantPool constantPool) {
        ValueNode receiver = args[0];
        // attempt to devirtualize the call
        RiResolvedType klass = target.holder();

        // 0. check for trivial cases
        if (target.canBeStaticallyBound() && !isAbstract(target.accessFlags())) {
            // check for trivial cases (e.g. final methods, nonvirtual methods)
            invokeDirect(target, args, target.holder(), cpi, constantPool);
            return;
        }
        // 1. check if the exact type of the receiver can be determined
        RiResolvedType exact = getExactType(klass, receiver);
        if (exact != null) {
            // either the holder class is exact, or the receiver object has an exact type
            invokeDirect(exact.resolveMethodImpl(target), args, exact, cpi, constantPool);
            return;
        }
        // devirtualization failed, produce an actual invokevirtual
        appendInvoke(opcode, target, args, cpi, constantPool);
    }

    private void invokeDirect(RiResolvedMethod target, ValueNode[] args, RiType knownHolder, int cpi, RiConstantPool constantPool) {
        appendInvoke(INVOKESPECIAL, target, args, cpi, constantPool);
    }

    private void appendInvoke(int opcode, RiResolvedMethod target, ValueNode[] args, int cpi, RiConstantPool constantPool) {
        CiKind resultType = target.signature().returnKind(false);
        if (GraalOptions.DeoptALot) {
            storeResultGraph = false;
            DeoptimizeNode deoptimize = graph.add(new DeoptimizeNode(DeoptAction.None));
            deoptimize.setMessage("invoke " + target.name());
            append(deoptimize);
            frameState.pushReturn(resultType, ConstantNode.defaultForKind(resultType, graph));
        } else {
            InvokeNode invoke = graph.add(new InvokeNode(bci(), opcode, resultType.stackKind(), args, target, target.signature().returnType(method.holder())));
            ValueNode result = appendWithBCI(invoke);
            invoke.setExceptionEdge(handleException(null, bci()));
            frameState.pushReturn(resultType, result);
        }
    }

    private RiResolvedType getExactType(RiResolvedType staticType, ValueNode receiver) {
        RiResolvedType exact = staticType.exactType();
        if (exact == null) {
            exact = receiver.exactType();
            if (exact == null) {
                if (receiver.isConstant()) {
                    exact = runtime.getTypeOf(receiver.asConstant());
                }
                if (exact == null) {
                    RiType declared = receiver.declaredType();
                    if (declared instanceof RiResolvedType) {
                        exact = ((RiResolvedType) declared).exactType();
                    }
                }
            }
        }
        return exact;
    }

    private void callRegisterFinalizer() {
        // append a call to the finalizer registration
        append(graph.add(new RegisterFinalizerNode(frameState.loadLocal(0))));
    }

    private void genReturn(ValueNode x) {
        frameState.clearStack();
        if (x != null) {
            frameState.push(x.kind, x);
        }
        appendGoto(createTarget(returnBlock(bci()), frameState));
    }

    private void genMonitorEnter(ValueNode x, int bci) {
        int lockNumber = frameState.locksSize();
        MonitorEnterNode monitorEnter = graph.add(new MonitorEnterNode(x, lockNumber));
        appendWithBCI(monitorEnter);
        frameState.lock(x);
        if (bci == FixedWithNextNode.SYNCHRONIZATION_ENTRY_BCI) {
            monitorEnter.setStateAfter(frameState.create(0));
        }
    }

    private void genMonitorExit(ValueNode x) {
        int lockNumber = frameState.locksSize() - 1;
        if (lockNumber < 0) {
            throw new CiBailout("monitor stack underflow");
        }
        appendWithBCI(graph.add(new MonitorExitNode(x, lockNumber)));
        frameState.unlock();
    }

    private void genJsr(int dest) {
        Block successor = currentBlock.jsrSuccessor;
        assert successor.startBci == dest : successor.startBci + " != " + dest + " @" + bci();
        JsrScope scope = currentBlock.jsrScope;
        if (!successor.jsrScope.pop().equals(scope)) {
            throw new JSRNotSupportedBailout("unstructured control flow (internal limitation)");
        }
        if (successor.jsrScope.nextReturnAddress() != stream().nextBCI()) {
            throw new JSRNotSupportedBailout("unstructured control flow (internal limitation)");
        }
        frameState.push(CiKind.Jsr, ConstantNode.forJsr(stream().nextBCI(), graph));
        appendGoto(createTarget(successor, frameState));
    }

    private void genRet(int localIndex) {
        Block successor = currentBlock.retSuccessor;
        ValueNode local = frameState.loadLocal(localIndex);
        JsrScope scope = currentBlock.jsrScope;
        int retAddress = scope.nextReturnAddress();
        append(graph.add(new FixedGuardNode(graph.unique(new CompareNode(local, Condition.EQ, ConstantNode.forJsr(retAddress, graph))))));
        if (!successor.jsrScope.equals(scope.pop())) {
            throw new JSRNotSupportedBailout("unstructured control flow (ret leaves more than one scope)");
        }
        appendGoto(createTarget(successor, frameState));
    }

    private void genTableswitch() {
        int bci = bci();
        ValueNode value = frameState.ipop();
        BytecodeTableSwitch ts = new BytecodeTableSwitch(stream(), bci);

        int nofCases = ts.numberOfCases() + 1; // including default case
        assert currentBlock.normalSuccessors == nofCases;

        TableSwitchNode tableSwitch = graph.add(new TableSwitchNode(value, ts.lowKey(), switchProbability(nofCases, bci)));
        for (int i = 0; i < nofCases; ++i) {
            tableSwitch.setBlockSuccessor(i, BeginNode.begin(createTarget(currentBlock.successors.get(i), frameState)));
        }
        append(tableSwitch);
    }

    private double[] switchProbability(int numberOfCases, int bci) {
        double[] prob = method.switchProbability(bci);
        if (prob != null) {
            assert prob.length == numberOfCases;
        } else {
            if (GraalOptions.TraceProbability) {
                TTY.println("Missing probability (switch) in " + method + " at bci " + bci);
            }
            prob = new double[numberOfCases];
            for (int i = 0; i < numberOfCases; i++) {
                prob[i] = 1.0d / numberOfCases;
            }
        }
        return prob;
    }

    private void genLookupswitch() {
        int bci = bci();
        ValueNode value = frameState.ipop();
        BytecodeLookupSwitch ls = new BytecodeLookupSwitch(stream(), bci);

        int nofCases = ls.numberOfCases() + 1; // including default case
        assert currentBlock.normalSuccessors == nofCases;

        int[] keys = new int[nofCases - 1];
        for (int i = 0; i < nofCases - 1; ++i) {
            keys[i] = ls.keyAt(i);
        }
        LookupSwitchNode lookupSwitch = graph.add(new LookupSwitchNode(value, keys, switchProbability(nofCases, bci)));
        for (int i = 0; i < nofCases; ++i) {
            lookupSwitch.setBlockSuccessor(i, BeginNode.begin(createTarget(currentBlock.successors.get(i), frameState)));
        }
        append(lookupSwitch);
    }

    private ConstantNode appendConstant(CiConstant constant) {
        return graph.unique(new ConstantNode(constant));
    }

    private ValueNode append(FixedNode fixed) {
        lastInstr.setNext(fixed);
        lastInstr = null;
        return fixed;
    }

    private ValueNode append(FixedWithNextNode x) {
        return appendWithBCI(x);
    }

    private ValueNode append(ValueNode v) {
        return v;
    }

    private ValueNode appendWithBCI(FixedWithNextNode x) {
        assert x.predecessor() == null : "instruction should not have been appended yet";
        assert lastInstr.next() == null : "cannot append instruction to instruction which isn't end (" + lastInstr + "->" + lastInstr.next() + ")";
        lastInstr.setNext(x);
        lastInstr = x;
        return x;
    }

    private FixedNode createTarget(Block block, FrameStateAccess stateAfter) {
        assert block != null && stateAfter != null;
        assert block.isLoopHeader || block.firstInstruction == null || block.firstInstruction.next() == null :
            "non-loop block must be iterated after all its predecessors. startBci=" + block.startBci + ", " + block.getClass().getSimpleName() + ", " + block.firstInstruction.next();

        if (block.isExceptionEntry) {
            assert stateAfter.stackSize() == 1;
        }

        if (block.firstInstruction == null) {
            if (block.isLoopHeader) {
                LoopBeginNode loopBegin = graph.add(new LoopBeginNode());
                loopBegin.addEnd(graph.add(new EndNode()));
                LoopEndNode loopEnd = graph.add(new LoopEndNode());
                loopEnd.setLoopBegin(loopBegin);
                PlaceholderNode pBegin = graph.add(new PlaceholderNode());
                pBegin.setNext(loopBegin.forwardEdge());
                PlaceholderNode pEnd = graph.add(new PlaceholderNode());
                pEnd.setNext(loopEnd);
                loopBegin.setStateAfter(stateAfter.duplicate(block.startBci));
                block.firstInstruction = pBegin;
            } else {
                block.firstInstruction = graph.add(new PlaceholderNode());
            }
        }
        mergeOrClone(block, stateAfter);
        addToWorkList(block);

        FixedNode result = null;
        if (block.isLoopHeader && isVisited(block)) {
            result = (StateSplit) loopBegin(block).loopEnd().predecessor();
        } else {
            result = block.firstInstruction;
        }

        assert result instanceof MergeNode || result instanceof PlaceholderNode : result;

        if (result instanceof MergeNode) {
            if (result instanceof LoopBeginNode) {
                result = ((LoopBeginNode) result).forwardEdge();
            } else {
                EndNode end = graph.add(new EndNode());
                ((MergeNode) result).addEnd(end);
                PlaceholderNode p = graph.add(new PlaceholderNode());
                int bci = block.startBci;
                if (block instanceof ExceptionBlock) {
                    bci = ((ExceptionBlock) block).deoptBci;
                }
                p.setStateAfter(stateAfter.duplicate(bci));
                p.setNext(end);
                result = p;
            }
        }
        assert !(result instanceof LoopBeginNode || result instanceof MergeNode);
        return result;
    }

    private ValueNode synchronizedObject(FrameStateAccess state, RiResolvedMethod target) {
        if (isStatic(target.accessFlags())) {
            ConstantNode classConstant = graph.unique(new ConstantNode(target.holder().getEncoding(Representation.JavaClass)));
            return append(classConstant);
        } else {
            return state.localAt(0);
        }
    }

    private void iterateAllBlocks() {
        Block block;
        while ((block = removeFromWorkList()) != null) {
            // remove blocks that have no predecessors by the time it their bytecodes are parsed
            if (block.firstInstruction == null) {
                markVisited(block);
                continue;
            }

            if (!isVisited(block)) {
                markVisited(block);
                // now parse the block
                if (block.isLoopHeader) {
                    LoopBeginNode begin = loopBegin(block);
                    FrameState preLoopState = ((StateSplit) block.firstInstruction).stateAfter();
                    assert preLoopState != null;
                    FrameState duplicate = preLoopState.duplicate(preLoopState.bci);
                    begin.setStateAfter(duplicate);
                    duplicate.insertLoopPhis(begin);
                    lastInstr = begin;
                } else {
                    lastInstr = block.firstInstruction;
                }
                frameState.initializeFrom(((StateSplit) lastInstr).stateAfter());
                assert lastInstr.next() == null : "instructions already appended at block " + block.blockID;

                if (block == returnBlock) {
                    createReturnBlock(block);
                } else if (block == unwindBlock) {
                    createUnwindBlock(block);
                } else if (block instanceof ExceptionBlock) {
                    createExceptionDispatch((ExceptionBlock) block);
                } else if (block instanceof DeoptBlock) {
                    createDeoptBlock((DeoptBlock) block);
                } else {
                    frameState.setRethrowException(false);
                    iterateBytecodesForBlock(block);
                }
            }
        }
        for (Block b : blocksVisited) {
            if (b.isLoopHeader) {
                LoopBeginNode begin = loopBegin(b);
                LoopEndNode loopEnd = begin.loopEnd();
                StateSplit loopEndPred = (StateSplit) loopEnd.predecessor();

//              This can happen with degenerated loops like this one:
//                for (;;) {
//                    try {
//                        break;
//                    } catch (UnresolvedException iioe) {
//                    }
//                }
                if (loopEndPred.stateAfter() != null) {
                    begin.stateAfter().merge(begin, loopEndPred.stateAfter());
                } else {
                    loopEndPred.delete();
                    loopEnd.delete();
                    MergeNode merge = graph.add(new MergeNode());
                    merge.addEnd(begin.forwardEdge());
                    FixedNode next = begin.next();
                    begin.setNext(null);
                    merge.setNext(next);
                    merge.setStateAfter(begin.stateAfter());
                    begin.replaceAndDelete(merge);
                }
            }
        }
    }

    private static LoopBeginNode loopBegin(Block block) {
        EndNode endNode = (EndNode) block.firstInstruction.next();
        LoopBeginNode loopBegin = (LoopBeginNode) endNode.merge();
        return loopBegin;
    }

    private void createDeoptBlock(DeoptBlock block) {
        storeResultGraph = false;
        append(graph.add(new DeoptimizeNode(DeoptAction.InvalidateReprofile)));
    }

    private void createUnwindBlock(Block block) {
        if (Modifier.isSynchronized(method.accessFlags())) {
            genMonitorExit(methodSynchronizedObject);
        }
        UnwindNode unwindNode = graph.add(new UnwindNode(frameState.apop()));
        append(unwindNode);
    }

    private void createReturnBlock(Block block) {
        if (method.isConstructor() && method.holder().superType() == null) {
            callRegisterFinalizer();
        }
        CiKind returnKind = method.signature().returnKind(false).stackKind();
        ValueNode x = returnKind == CiKind.Void ? null : frameState.pop(returnKind);
        assert frameState.stackSize() == 0;

        if (Modifier.isSynchronized(method.accessFlags())) {
            genMonitorExit(methodSynchronizedObject);
        }
        ReturnNode returnNode = graph.add(new ReturnNode(x));
        append(returnNode);
    }

    private void createExceptionDispatch(ExceptionBlock block) {
        if (block.handler == null) {
            assert frameState.stackSize() == 1 : "only exception object expected on stack, actual size: " + frameState.stackSize();
            createUnwindBlock(block);
        } else {
            assert frameState.stackSize() == 1 : frameState;

            RiType catchType = block.handler.catchType();
            ConstantNode typeInstruction = genTypeOrDeopt(RiType.Representation.ObjectHub, catchType, catchType instanceof RiResolvedType);
            if (typeInstruction != null) {
                Block nextBlock = block.successors.size() == 1 ? unwindBlock(block.deoptBci) : block.successors.get(1);

                FixedNode catchSuccessor = createTarget(block.successors.get(0), frameState);
                FixedNode nextDispatch = createTarget(nextBlock, frameState);
                ValueNode exception = frameState.stackAt(0);
                IfNode ifNode = graph.add(new IfNode(graph.unique(new InstanceOfNode(typeInstruction, exception)), catchSuccessor, nextDispatch, 0.5));
                append(ifNode);
            }
        }
    }

    private void appendGoto(FixedNode target) {
        if (lastInstr != null) {
            lastInstr.setNext(target);
        }
    }

    private void iterateBytecodesForBlock(Block block) {
        assert frameState != null;

        currentBlock = block;

        int endBCI = stream.endBCI();

        stream.setBCI(block.startBci);
        int bci = block.startBci;
        while (bci < endBCI) {
            // read the opcode
            int opcode = stream.currentBC();
            traceState();
            traceInstruction(bci, opcode, bci == block.startBci);
            processBytecode(bci, opcode);

            if (lastInstr == null || IdentifyBlocksPhase.isBlockEnd(lastInstr) || lastInstr.next() != null) {
                break;
            }

            stream.next();
            bci = stream.currentBCI();
            if (lastInstr instanceof StateSplit) {
                StateSplit stateSplit = (StateSplit) lastInstr;
                if (stateSplit.stateAfter() == null && stateSplit.needsStateAfter()) {
                    stateSplit.setStateAfter(frameState.create(bci));
                }
            }
            if (bci < endBCI) {
                if (bci > block.endBci) {
                    assert !block.successors.get(0).isExceptionEntry;
                    assert block.normalSuccessors == 1;
                    // we fell through to the next block, add a goto and break
                    appendGoto(createTarget(block.successors.get(0), frameState));
                    break;
                }
            }
        }
    }

    private void traceState() {
        if (GraalOptions.TraceBytecodeParserLevel >= TRACELEVEL_STATE && !TTY.isSuppressed()) {
            log.println(String.format("|   state [nr locals = %d, stack depth = %d, method = %s]", frameState.localsSize(), frameState.stackSize(), method));
            for (int i = 0; i < frameState.localsSize(); ++i) {
                ValueNode value = frameState.localAt(i);
                log.println(String.format("|   local[%d] = %-8s : %s", i, value == null ? "bogus" : value.kind.javaName, value));
            }
            for (int i = 0; i < frameState.stackSize(); ++i) {
                ValueNode value = frameState.stackAt(i);
                log.println(String.format("|   stack[%d] = %-8s : %s", i, value == null ? "bogus" : value.kind.javaName, value));
            }
            for (int i = 0; i < frameState.locksSize(); ++i) {
                ValueNode value = frameState.lockAt(i);
                log.println(String.format("|   lock[%d] = %-8s : %s", i, value == null ? "bogus" : value.kind.javaName, value));
            }
        }
    }

    private void processBytecode(int bci, int opcode) {
        int cpi;

        // Checkstyle: stop
        switch (opcode) {
            case NOP            : /* nothing to do */ break;
            case ACONST_NULL    : frameState.apush(appendConstant(CiConstant.NULL_OBJECT)); break;
            case ICONST_M1      : frameState.ipush(appendConstant(CiConstant.INT_MINUS_1)); break;
            case ICONST_0       : frameState.ipush(appendConstant(CiConstant.INT_0)); break;
            case ICONST_1       : frameState.ipush(appendConstant(CiConstant.INT_1)); break;
            case ICONST_2       : frameState.ipush(appendConstant(CiConstant.INT_2)); break;
            case ICONST_3       : frameState.ipush(appendConstant(CiConstant.INT_3)); break;
            case ICONST_4       : frameState.ipush(appendConstant(CiConstant.INT_4)); break;
            case ICONST_5       : frameState.ipush(appendConstant(CiConstant.INT_5)); break;
            case LCONST_0       : frameState.lpush(appendConstant(CiConstant.LONG_0)); break;
            case LCONST_1       : frameState.lpush(appendConstant(CiConstant.LONG_1)); break;
            case FCONST_0       : frameState.fpush(appendConstant(CiConstant.FLOAT_0)); break;
            case FCONST_1       : frameState.fpush(appendConstant(CiConstant.FLOAT_1)); break;
            case FCONST_2       : frameState.fpush(appendConstant(CiConstant.FLOAT_2)); break;
            case DCONST_0       : frameState.dpush(appendConstant(CiConstant.DOUBLE_0)); break;
            case DCONST_1       : frameState.dpush(appendConstant(CiConstant.DOUBLE_1)); break;
            case BIPUSH         : frameState.ipush(appendConstant(CiConstant.forInt(stream.readByte()))); break;
            case SIPUSH         : frameState.ipush(appendConstant(CiConstant.forInt(stream.readShort()))); break;
            case LDC            : // fall through
            case LDC_W          : // fall through
            case LDC2_W         : genLoadConstant(stream.readCPI()); break;
            case ILOAD          : loadLocal(stream.readLocalIndex(), CiKind.Int); break;
            case LLOAD          : loadLocal(stream.readLocalIndex(), CiKind.Long); break;
            case FLOAD          : loadLocal(stream.readLocalIndex(), CiKind.Float); break;
            case DLOAD          : loadLocal(stream.readLocalIndex(), CiKind.Double); break;
            case ALOAD          : loadLocal(stream.readLocalIndex(), CiKind.Object); break;
            case ILOAD_0        : // fall through
            case ILOAD_1        : // fall through
            case ILOAD_2        : // fall through
            case ILOAD_3        : loadLocal(opcode - ILOAD_0, CiKind.Int); break;
            case LLOAD_0        : // fall through
            case LLOAD_1        : // fall through
            case LLOAD_2        : // fall through
            case LLOAD_3        : loadLocal(opcode - LLOAD_0, CiKind.Long); break;
            case FLOAD_0        : // fall through
            case FLOAD_1        : // fall through
            case FLOAD_2        : // fall through
            case FLOAD_3        : loadLocal(opcode - FLOAD_0, CiKind.Float); break;
            case DLOAD_0        : // fall through
            case DLOAD_1        : // fall through
            case DLOAD_2        : // fall through
            case DLOAD_3        : loadLocal(opcode - DLOAD_0, CiKind.Double); break;
            case ALOAD_0        : // fall through
            case ALOAD_1        : // fall through
            case ALOAD_2        : // fall through
            case ALOAD_3        : loadLocal(opcode - ALOAD_0, CiKind.Object); break;
            case IALOAD         : genLoadIndexed(CiKind.Int   ); break;
            case LALOAD         : genLoadIndexed(CiKind.Long  ); break;
            case FALOAD         : genLoadIndexed(CiKind.Float ); break;
            case DALOAD         : genLoadIndexed(CiKind.Double); break;
            case AALOAD         : genLoadIndexed(CiKind.Object); break;
            case BALOAD         : genLoadIndexed(CiKind.Byte  ); break;
            case CALOAD         : genLoadIndexed(CiKind.Char  ); break;
            case SALOAD         : genLoadIndexed(CiKind.Short ); break;
            case ISTORE         : storeLocal(CiKind.Int, stream.readLocalIndex()); break;
            case LSTORE         : storeLocal(CiKind.Long, stream.readLocalIndex()); break;
            case FSTORE         : storeLocal(CiKind.Float, stream.readLocalIndex()); break;
            case DSTORE         : storeLocal(CiKind.Double, stream.readLocalIndex()); break;
            case ASTORE         : storeLocal(CiKind.Object, stream.readLocalIndex()); break;
            case ISTORE_0       : // fall through
            case ISTORE_1       : // fall through
            case ISTORE_2       : // fall through
            case ISTORE_3       : storeLocal(CiKind.Int, opcode - ISTORE_0); break;
            case LSTORE_0       : // fall through
            case LSTORE_1       : // fall through
            case LSTORE_2       : // fall through
            case LSTORE_3       : storeLocal(CiKind.Long, opcode - LSTORE_0); break;
            case FSTORE_0       : // fall through
            case FSTORE_1       : // fall through
            case FSTORE_2       : // fall through
            case FSTORE_3       : storeLocal(CiKind.Float, opcode - FSTORE_0); break;
            case DSTORE_0       : // fall through
            case DSTORE_1       : // fall through
            case DSTORE_2       : // fall through
            case DSTORE_3       : storeLocal(CiKind.Double, opcode - DSTORE_0); break;
            case ASTORE_0       : // fall through
            case ASTORE_1       : // fall through
            case ASTORE_2       : // fall through
            case ASTORE_3       : storeLocal(CiKind.Object, opcode - ASTORE_0); break;
            case IASTORE        : genStoreIndexed(CiKind.Int   ); break;
            case LASTORE        : genStoreIndexed(CiKind.Long  ); break;
            case FASTORE        : genStoreIndexed(CiKind.Float ); break;
            case DASTORE        : genStoreIndexed(CiKind.Double); break;
            case AASTORE        : genStoreIndexed(CiKind.Object); break;
            case BASTORE        : genStoreIndexed(CiKind.Byte  ); break;
            case CASTORE        : genStoreIndexed(CiKind.Char  ); break;
            case SASTORE        : genStoreIndexed(CiKind.Short ); break;
            case POP            : // fall through
            case POP2           : // fall through
            case DUP            : // fall through
            case DUP_X1         : // fall through
            case DUP_X2         : // fall through
            case DUP2           : // fall through
            case DUP2_X1        : // fall through
            case DUP2_X2        : // fall through
            case SWAP           : stackOp(opcode); break;
            case IADD           : // fall through
            case ISUB           : // fall through
            case IMUL           : genArithmeticOp(CiKind.Int, opcode, false); break;
            case IDIV           : // fall through
            case IREM           : genArithmeticOp(CiKind.Int, opcode, true); break;
            case LADD           : // fall through
            case LSUB           : // fall through
            case LMUL           : genArithmeticOp(CiKind.Long, opcode, false); break;
            case LDIV           : // fall through
            case LREM           : genArithmeticOp(CiKind.Long, opcode, true); break;
            case FADD           : // fall through
            case FSUB           : // fall through
            case FMUL           : // fall through
            case FDIV           : // fall through
            case FREM           : genArithmeticOp(CiKind.Float, opcode, false); break;
            case DADD           : // fall through
            case DSUB           : // fall through
            case DMUL           : // fall through
            case DDIV           : // fall through
            case DREM           : genArithmeticOp(CiKind.Double, opcode, false); break;
            case INEG           : genNegateOp(CiKind.Int); break;
            case LNEG           : genNegateOp(CiKind.Long); break;
            case FNEG           : genNegateOp(CiKind.Float); break;
            case DNEG           : genNegateOp(CiKind.Double); break;
            case ISHL           : // fall through
            case ISHR           : // fall through
            case IUSHR          : genShiftOp(CiKind.Int, opcode); break;
            case IAND           : // fall through
            case IOR            : // fall through
            case IXOR           : genLogicOp(CiKind.Int, opcode); break;
            case LSHL           : // fall through
            case LSHR           : // fall through
            case LUSHR          : genShiftOp(CiKind.Long, opcode); break;
            case LAND           : // fall through
            case LOR            : // fall through
            case LXOR           : genLogicOp(CiKind.Long, opcode); break;
            case IINC           : genIncrement(); break;
            case I2L            : genConvert(ConvertNode.Op.I2L, CiKind.Int   , CiKind.Long  ); break;
            case I2F            : genConvert(ConvertNode.Op.I2F, CiKind.Int   , CiKind.Float ); break;
            case I2D            : genConvert(ConvertNode.Op.I2D, CiKind.Int   , CiKind.Double); break;
            case L2I            : genConvert(ConvertNode.Op.L2I, CiKind.Long  , CiKind.Int   ); break;
            case L2F            : genConvert(ConvertNode.Op.L2F, CiKind.Long  , CiKind.Float ); break;
            case L2D            : genConvert(ConvertNode.Op.L2D, CiKind.Long  , CiKind.Double); break;
            case F2I            : genConvert(ConvertNode.Op.F2I, CiKind.Float , CiKind.Int   ); break;
            case F2L            : genConvert(ConvertNode.Op.F2L, CiKind.Float , CiKind.Long  ); break;
            case F2D            : genConvert(ConvertNode.Op.F2D, CiKind.Float , CiKind.Double); break;
            case D2I            : genConvert(ConvertNode.Op.D2I, CiKind.Double, CiKind.Int   ); break;
            case D2L            : genConvert(ConvertNode.Op.D2L, CiKind.Double, CiKind.Long  ); break;
            case D2F            : genConvert(ConvertNode.Op.D2F, CiKind.Double, CiKind.Float ); break;
            case I2B            : genConvert(ConvertNode.Op.I2B, CiKind.Int   , CiKind.Byte  ); break;
            case I2C            : genConvert(ConvertNode.Op.I2C, CiKind.Int   , CiKind.Char  ); break;
            case I2S            : genConvert(ConvertNode.Op.I2S, CiKind.Int   , CiKind.Short ); break;
            case LCMP           : genCompareOp(CiKind.Long, opcode, CiKind.Int); break;
            case FCMPL          : genCompareOp(CiKind.Float, opcode, CiKind.Int); break;
            case FCMPG          : genCompareOp(CiKind.Float, opcode, CiKind.Int); break;
            case DCMPL          : genCompareOp(CiKind.Double, opcode, CiKind.Int); break;
            case DCMPG          : genCompareOp(CiKind.Double, opcode, CiKind.Int); break;
            case IFEQ           : genIfZero(Condition.EQ); break;
            case IFNE           : genIfZero(Condition.NE); break;
            case IFLT           : genIfZero(Condition.LT); break;
            case IFGE           : genIfZero(Condition.GE); break;
            case IFGT           : genIfZero(Condition.GT); break;
            case IFLE           : genIfZero(Condition.LE); break;
            case IF_ICMPEQ      : genIfSame(CiKind.Int, Condition.EQ); break;
            case IF_ICMPNE      : genIfSame(CiKind.Int, Condition.NE); break;
            case IF_ICMPLT      : genIfSame(CiKind.Int, Condition.LT); break;
            case IF_ICMPGE      : genIfSame(CiKind.Int, Condition.GE); break;
            case IF_ICMPGT      : genIfSame(CiKind.Int, Condition.GT); break;
            case IF_ICMPLE      : genIfSame(CiKind.Int, Condition.LE); break;
            case IF_ACMPEQ      : genIfSame(CiKind.Object, Condition.EQ); break;
            case IF_ACMPNE      : genIfSame(CiKind.Object, Condition.NE); break;
            case GOTO           : genGoto(stream.readBranchDest()); break;
            case JSR            : genJsr(stream.readBranchDest()); break;
            case RET            : genRet(stream.readLocalIndex()); break;
            case TABLESWITCH    : genTableswitch(); break;
            case LOOKUPSWITCH   : genLookupswitch(); break;
            case IRETURN        : genReturn(frameState.ipop()); break;
            case LRETURN        : genReturn(frameState.lpop()); break;
            case FRETURN        : genReturn(frameState.fpop()); break;
            case DRETURN        : genReturn(frameState.dpop()); break;
            case ARETURN        : genReturn(frameState.apop()); break;
            case RETURN         : genReturn(null); break;
            case GETSTATIC      : cpi = stream.readCPI(); genGetStatic(cpi, lookupField(cpi, opcode)); break;
            case PUTSTATIC      : cpi = stream.readCPI(); genPutStatic(cpi, lookupField(cpi, opcode)); break;
            case GETFIELD       : cpi = stream.readCPI(); genGetField(cpi, lookupField(cpi, opcode)); break;
            case PUTFIELD       : cpi = stream.readCPI(); genPutField(cpi, lookupField(cpi, opcode)); break;
            case INVOKEVIRTUAL  : cpi = stream.readCPI(); genInvokeVirtual(lookupMethod(cpi, opcode), cpi, constantPool); break;
            case INVOKESPECIAL  : cpi = stream.readCPI(); genInvokeSpecial(lookupMethod(cpi, opcode), null, cpi, constantPool); break;
            case INVOKESTATIC   : cpi = stream.readCPI(); genInvokeStatic(lookupMethod(cpi, opcode), cpi, constantPool); break;
            case INVOKEINTERFACE: cpi = stream.readCPI(); genInvokeInterface(lookupMethod(cpi, opcode), cpi, constantPool); break;
            case NEW            : genNewInstance(stream.readCPI()); break;
            case NEWARRAY       : genNewTypeArray(stream.readLocalIndex()); break;
            case ANEWARRAY      : genNewObjectArray(stream.readCPI()); break;
            case ARRAYLENGTH    : genArrayLength(); break;
            case ATHROW         : genThrow(stream.currentBCI()); break;
            case CHECKCAST      : genCheckCast(); break;
            case INSTANCEOF     : genInstanceOf(); break;
            case MONITORENTER   : genMonitorEnter(frameState.apop(), stream.currentBCI()); break;
            case MONITOREXIT    : genMonitorExit(frameState.apop()); break;
            case MULTIANEWARRAY : genNewMultiArray(stream.readCPI()); break;
            case IFNULL         : genIfNull(Condition.EQ); break;
            case IFNONNULL      : genIfNull(Condition.NE); break;
            case GOTO_W         : genGoto(stream.readFarBranchDest()); break;
            case JSR_W          : genJsr(stream.readFarBranchDest()); break;
            case BREAKPOINT:
                throw new CiBailout("concurrent setting of breakpoint");
            default:
                throw new CiBailout("Unsupported opcode " + opcode + " (" + nameOf(opcode) + ") [bci=" + bci + "]");
        }
        // Checkstyle: resume
    }

    private void traceInstruction(int bci, int opcode, boolean blockStart) {
        if (GraalOptions.TraceBytecodeParserLevel >= TRACELEVEL_INSTRUCTIONS && !TTY.isSuppressed()) {
            StringBuilder sb = new StringBuilder(40);
            sb.append(blockStart ? '+' : '|');
            if (bci < 10) {
                sb.append("  ");
            } else if (bci < 100) {
                sb.append(' ');
            }
            sb.append(bci).append(": ").append(Bytecodes.nameOf(opcode));
            for (int i = bci + 1; i < stream.nextBCI(); ++i) {
                sb.append(' ').append(stream.readUByte(i));
            }
            if (!currentBlock.jsrScope.isEmpty()) {
                sb.append(' ').append(currentBlock.jsrScope);
            }
            log.println(sb.toString());
        }
    }

    private void genArrayLength() {
        frameState.ipush(append(graph.add(new ArrayLengthNode(frameState.apop()))));
    }

    /**
     * Adds a block to the worklist, if it is not already in the worklist.
     * This method will keep the worklist topologically stored (i.e. the lower
     * DFNs are earlier in the list).
     * @param block the block to add to the work list
     */
    private void addToWorkList(Block block) {
        if (!isOnWorkList(block)) {
            markOnWorkList(block);
            sortIntoWorkList(block);
        }
    }

    private void sortIntoWorkList(Block top) {
        workList.offer(top);
    }

    /**
     * Removes the next block from the worklist. The list is sorted topologically, so the
     * block with the lowest depth first number in the list will be removed and returned.
     * @return the next block from the worklist; {@code null} if there are no blocks
     * in the worklist
     */
    private Block removeFromWorkList() {
        return workList.poll();
    }
}