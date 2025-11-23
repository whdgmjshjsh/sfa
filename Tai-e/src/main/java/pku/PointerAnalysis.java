package pku;

import pascal.taie.World;
import pascal.taie.analysis.ProgramAnalysis;
import pascal.taie.config.AnalysisConfig;
import pascal.taie.ir.IR;
import pascal.taie.ir.exp.ArrayAccess;
import pascal.taie.ir.exp.CastExp;
import pascal.taie.ir.exp.FieldAccess;
import pascal.taie.ir.exp.InstanceFieldAccess;
import pascal.taie.ir.exp.InvokeExp;
import pascal.taie.ir.exp.StaticFieldAccess;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.proginfo.FieldRef;
import pascal.taie.ir.stmt.Cast;
import pascal.taie.ir.stmt.Copy;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.LoadArray;
import pascal.taie.ir.stmt.LoadField;
import pascal.taie.ir.stmt.New;
import pascal.taie.ir.stmt.StoreArray;
import pascal.taie.ir.stmt.StoreField;
import pascal.taie.language.classes.ClassHierarchy;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.ReferenceType;
import pascal.taie.language.type.Type;
import pascal.taie.language.type.TypeSystem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class PointerAnalysis extends PointerAnalysisTrivial {
    public static final String ID = "pku-pta";

    public PointerAnalysis(AnalysisConfig config) {
        super(config);
    }

    @Override
    public PointerAnalysisResult analyze() {
        var world = World.get();
        var preprocess = new PreprocessResult();

        List<New> news = new ArrayList<>();
        List<Copy> copies = new ArrayList<>();
        List<Cast> casts = new ArrayList<>();
        List<StoreField> storeFields = new ArrayList<>();
        List<LoadField> loadFields = new ArrayList<>();
        List<StoreArray> storeArrays = new ArrayList<>();
        List<LoadArray> loadArrays = new ArrayList<>();
        List<Invoke> invokes = new ArrayList<>();
        Map<JMethod, IR> methodIRs = new HashMap<>();

        World.get().getClassHierarchy().applicationClasses().forEach(jclass -> {
            jclass.getDeclaredMethods().forEach(method -> {
                if (method.isAbstract()) {
                    return;
                }
                IR ir = method.getIR();
                methodIRs.put(method, ir);
                preprocess.analysis(ir);
                ir.getStmts().forEach(stmt -> {
                    if (stmt instanceof New) {
                        news.add((New) stmt);
                    } else if (stmt instanceof Copy) {
                        copies.add((Copy) stmt);
                    } else if (stmt instanceof Cast) {
                        casts.add((Cast) stmt);
                    } else if (stmt instanceof StoreField) {
                        storeFields.add((StoreField) stmt);
                    } else if (stmt instanceof LoadField) {
                        loadFields.add((LoadField) stmt);
                    } else if (stmt instanceof StoreArray) {
                        storeArrays.add((StoreArray) stmt);
                    } else if (stmt instanceof LoadArray) {
                        loadArrays.add((LoadArray) stmt);
                    } else if (stmt instanceof Invoke) {
                        invokes.add((Invoke) stmt);
                    }
                });
            });
        });

        var solver = new PointsToSolver(world.getClassHierarchy(), world.getTypeSystem(), preprocess,
                news, copies, casts, storeFields, loadFields, storeArrays, loadArrays,
                invokes, methodIRs);
        var ptsResult = solver.solve();

        dump(ptsResult);
        return ptsResult;
    }

    /**
     * A simple flow-insensitive, context-insensitive Andersen-style PTA.
     */
    private static class PointsToSolver {

        private final ClassHierarchy classHierarchy;
        private final TypeSystem typeSystem;
        private final PreprocessResult preprocess;

        private final List<New> news;
        private final List<Copy> copies;
        private final List<Cast> casts;
        private final List<StoreField> storeFields;
        private final List<LoadField> loadFields;
        private final List<StoreArray> storeArrays;
        private final List<LoadArray> loadArrays;
        private final List<Invoke> invokes;
        private final Map<JMethod, IR> methodIRs;

        private final Map<Var, Set<New>> varPointsTo = new HashMap<>();
        private final Map<FieldSlot, Set<New>> fieldPointsTo = new HashMap<>();
        private final Map<FieldRef, Set<New>> staticFieldPointsTo = new HashMap<>();
        private final Set<New> allAllocs = new HashSet<>();
        private final Map<Type, Set<New>> allAllocsByType = new HashMap<>();
        private final Set<Var> conservativeVars = new HashSet<>();

        PointsToSolver(ClassHierarchy classHierarchy,
                       TypeSystem typeSystem,
                       PreprocessResult preprocess,
                       List<New> news,
                       List<Copy> copies,
                       List<Cast> casts,
                       List<StoreField> storeFields,
                       List<LoadField> loadFields,
                       List<StoreArray> storeArrays,
                       List<LoadArray> loadArrays,
                       List<Invoke> invokes,
                       Map<JMethod, IR> methodIRs) {
            this.classHierarchy = classHierarchy;
            this.typeSystem = typeSystem;
            this.preprocess = preprocess;
            this.news = news;
            this.copies = copies;
            this.casts = casts;
            this.storeFields = storeFields;
            this.loadFields = loadFields;
            this.storeArrays = storeArrays;
            this.loadArrays = loadArrays;
            this.invokes = invokes;
            this.methodIRs = methodIRs;
        }

        PointerAnalysisResult solve() {
            // Seed points-to sets with allocation sites.
            news.forEach(n -> {
                addVarPoints(n.getLValue(), n);
                allAllocs.add(n);
            });
            // Pre-compute a fast lookup for type-based fallbacks.
            for (New obj : allAllocs) {
                Type objType = obj.getRValue().getType();
                allAllocsByType.computeIfAbsent(objType, k -> new HashSet<>()).add(obj);
            }

            boolean changed;
            do {
                changed = false;
                changed |= processCopies();
                changed |= processCasts();
                changed |= processStoreFields();
                changed |= processLoadFields();
                changed |= processStoreArrays();
                changed |= processLoadArrays();
                changed |= processInvokes();
            } while (changed);

            // For variables involved in conservative handling, ensure they
            // over-approximate all compatible allocations.
            conservativeVars.forEach(v ->
                    addAll(varPointsTo, v, allTypedAllocs(v.getType())));

            return buildResult();
        }

        private boolean processCopies() {
            boolean changed = false;
            for (Copy copy : copies) {
                Var src = copy.getRValue();
                Var dst = copy.getLValue();
                if (isReference(src) && isReference(dst)) {
                    changed |= addAll(varPointsTo, dst, getVarPoints(src));
                }
            }
            return changed;
        }

        private boolean processCasts() {
            boolean changed = false;
            for (Cast cast : casts) {
                CastExp exp = cast.getRValue();
                Var src = exp.getValue();
                Var dst = cast.getLValue();
                if (isReference(src) && isReference(dst)) {
                    changed |= addAll(varPointsTo, dst,
                            filterByType(getVarPoints(src), exp.getCastType()));
                }
            }
            return changed;
        }

        private boolean processStoreFields() {
            boolean changed = false;
            for (StoreField sf : storeFields) {
                FieldAccess fa = sf.getFieldAccess();
                Var rhs = sf.getRValue();
                if (!(rhs.getType() instanceof ReferenceType)) {
                    continue;
                }
                if (fa instanceof InstanceFieldAccess) {
                    InstanceFieldAccess inst = (InstanceFieldAccess) fa;
                    for (New baseObj : candidateBases(inst.getBase())) {
                        changed |= addFieldPoints(
                                new FieldSlot(baseObj, inst.getFieldRef()),
                                getVarPoints(rhs));
                    }
                } else if (fa instanceof StaticFieldAccess) {
                    changed |= addAll(staticFieldPointsTo,
                            ((StaticFieldAccess) fa).getFieldRef(),
                            getVarPoints(rhs));
                }
            }
            return changed;
        }

        private boolean processLoadFields() {
            boolean changed = false;
            for (LoadField lf : loadFields) {
                FieldAccess fa = lf.getFieldAccess();
                Var lhs = lf.getLValue();
                if (!isReference(lhs)) {
                    continue;
                }
                if (fa instanceof InstanceFieldAccess) {
                    InstanceFieldAccess inst = (InstanceFieldAccess) fa;
                    for (New baseObj : candidateBases(inst.getBase())) {
                        changed |= addAll(varPointsTo, lhs,
                                getFieldPoints(new FieldSlot(baseObj, inst.getFieldRef())));
                    }
                } else if (fa instanceof StaticFieldAccess) {
                    changed |= addAll(varPointsTo, lhs,
                            staticFieldPointsTo.computeIfAbsent(
                                    ((StaticFieldAccess) fa).getFieldRef(),
                                    k -> new HashSet<>()));
                }
            }
            return changed;
        }

        private boolean processStoreArrays() {
            boolean changed = false;
            for (StoreArray sa : storeArrays) {
                ArrayAccess access = sa.getArrayAccess();
                Var rhs = sa.getRValue();
                if (!(rhs.getType() instanceof ReferenceType)) {
                    continue;
                }
                for (New baseObj : candidateBases(access.getBase())) {
                    changed |= addArrayStore(baseObj, access.getIndex(),
                            getVarPoints(rhs));
                }
            }
            return changed;
        }

        private boolean processLoadArrays() {
            boolean changed = false;
            for (LoadArray la : loadArrays) {
                Var lhs = la.getLValue();
                if (!isReference(lhs)) {
                    continue;
                }
                ArrayAccess access = la.getArrayAccess();
                for (New baseObj : candidateBases(access.getBase())) {
                    changed |= addAll(varPointsTo, lhs,
                            getArrayPoints(baseObj, access.getIndex()));
                }
            }
            return changed;
        }

        private boolean processInvokes() {
            boolean changed = false;
            for (Invoke invoke : invokes) {
                InvokeExp exp = invoke.getInvokeExp();
                String clsName = exp.getMethodRef().getDeclaringClass().getName();
                // Ignore Benchmark helpers; they only provide annotations.
                if (clsName.startsWith("benchmark.internal.Benchmark")) {
                    continue;
                }

                Var baseVar = invoke.isStatic() ? null : ((pascal.taie.ir.exp.InvokeInstanceExp) exp).getBase();
                boolean hasReceiverInfo = invoke.isStatic() || !candidateBases(baseVar).isEmpty();
                Set<JMethod> targets = resolveTargets(invoke);
                boolean needConservative = targets.isEmpty();
                for (JMethod target : targets) {
                    boolean isApp = target.getDeclaringClass().isApplication();
                    boolean useIR = isApp && !target.isAbstract() && !target.isNative();
                    IR ir = null;
                    if (useIR) {
                        try {
                            ir = methodIRs.computeIfAbsent(target, JMethod::getIR);
                        } catch (Exception e) {
                            ir = null;
                            useIR = false;
                        }
                    }
                    if (useIR && ir != null) {
                        if (!target.isStatic()) {
                            Var thisVar = ir.getThis();
                            if (thisVar != null && isReference(baseVar) && isReference(thisVar)) {
                                changed |= addAll(varPointsTo, thisVar, getVarPoints(baseVar));
                            }
                        }
                        List<Var> params = ir.getParams();
                        for (int i = 0; i < params.size(); i++) {
                            Var actual = exp.getArg(i);
                            Var formal = params.get(i);
                            if (isReference(actual) && isReference(formal)) {
                                changed |= addAll(varPointsTo, formal, getVarPoints(actual));
                            }
                        }
                        Var ret = invoke.getResult();
                        if (ret != null && isReference(ret)) {
                            for (Var rv : ir.getReturnVars()) {
                                if (isReference(rv)) {
                                    changed |= addAll(varPointsTo, ret,
                                            filterByType(getVarPoints(rv), ret.getType()));
                                }
                            }
                        }
                    } else {
                        if (hasReceiverInfo || invoke.isStatic()) {
                            needConservative = true;
                        }
                    }
                }
                Var ret = invoke.getResult();
                if (ret != null && isReference(ret) && needConservative) {
                    Set<New> over = conservativeReturn(invoke, ret);
                    if (!over.isEmpty()) {
                        changed |= addAll(varPointsTo, ret, over);
                    }
                    conservativeVars.add(ret);
                }
                if (needConservative) {
                    markConservativeArgs(invoke);
                }
                if (needConservative) {
                    changed |= conservativeArgs(invoke);
                }
            }
            return changed;
        }

        private Set<JMethod> resolveTargets(Invoke invoke) {
            Set<JMethod> targets = new HashSet<>();
            InvokeExp exp = invoke.getInvokeExp();
            if (invoke.isStatic()) {
                JMethod m = exp.getMethodRef().resolveNullable();
                if (m != null && !m.isAbstract()) {
                    targets.add(m);
                }
                return targets;
            }
            if (invoke.isDynamic()) {
                return targets;
            }
            Var base = ((pascal.taie.ir.exp.InvokeInstanceExp) exp).getBase();
            for (New obj : candidateBases(base)) {
                Type receiverType = obj.getRValue().getType();
                JMethod target = classHierarchy.dispatch(receiverType, exp.getMethodRef());
                if (target != null && !target.isAbstract()) {
                    targets.add(target);
                }
            }
            return targets;
        }

        private PointerAnalysisResult buildResult() {
            PointerAnalysisResult result = new PointerAnalysisResult();
            preprocess.test_pts.forEach((id, vars) -> {
                Set<Integer> objIds = new TreeSet<>();
                for (Var var : vars) {
                    Set<New> pts = varPointsTo.get(var);
                    if (pts != null) {
                        for (New obj : pts) {
                            Set<Integer> labels = preprocess.obj_ids.get(obj);
                            if (labels != null) {
                                objIds.addAll(labels);
                            }
                        }
                    }
                    if (isReference(var) && (pts == null || pts.isEmpty() || conservativeVars.contains(var))) {
                        preprocess.obj_ids.forEach((alloc, label) -> {
                            if (typeCompatible(alloc, var.getType())) {
                                objIds.addAll(label);
                            }
                        });
                    }
                }
                result.put(id, new TreeSet<>(objIds));
            });
            return result;
        }

        private Set<New> conservativeReturn(Invoke invoke, Var retVar) {
            Set<New> over = new HashSet<>();
            Type retType = retVar.getType();
            over.addAll(allTypedAllocs(retType));
            InvokeExp exp = invoke.getInvokeExp();
            if (!invoke.isStatic()) {
                Var base = ((pascal.taie.ir.exp.InvokeInstanceExp) exp).getBase();
                over.addAll(filterByType(candidateBases(base), retType));
            }
            for (Var arg : exp.getArgs()) {
                if (isReference(arg)) {
                    over.addAll(filterByType(getVarPoints(arg), retType));
                }
            }
            return over;
        }

        private boolean conservativeArgs(Invoke invoke) {
            boolean changed = false;
            List<Var> refs = new ArrayList<>();
            InvokeExp exp = invoke.getInvokeExp();
            if (!invoke.isStatic()) {
                Var base = ((pascal.taie.ir.exp.InvokeInstanceExp) exp).getBase();
                if (isReference(base)) {
                    refs.add(base);
                }
            }
            for (Var arg : exp.getArgs()) {
                if (isReference(arg)) {
                    refs.add(arg);
                }
            }
            for (Var src : refs) {
                Set<New> pts = getVarPoints(src);
                if (pts.isEmpty()) {
                    pts = allTypedAllocs(src.getType());
                }
                for (Var dst : refs) {
                    if (dst == src) {
                        continue;
                    }
                    changed |= addAll(varPointsTo, dst, filterByType(pts, dst.getType()));
                }
            }
            return changed;
        }

        private void markConservativeArgs(Invoke invoke) {
            InvokeExp exp = invoke.getInvokeExp();
            if (!invoke.isStatic()) {
                Var base = ((pascal.taie.ir.exp.InvokeInstanceExp) exp).getBase();
                if (isReference(base)) {
                    conservativeVars.add(base);
                }
            }
            for (Var arg : exp.getArgs()) {
                if (isReference(arg)) {
                    conservativeVars.add(arg);
                }
            }
        }

        private boolean addArrayStore(New baseObj, Var indexVar, Set<New> values) {
            ArrayIndex key = ArrayIndex.fromIndexVar(indexVar);
            if (key.isAny()) {
                return addFieldPoints(new FieldSlot(baseObj, key), values);
            }
            boolean changed = addFieldPoints(new FieldSlot(baseObj, key), values);
            return changed;
        }

        private Set<New> candidateBases(Var base) {
            if (base == null || !isReference(base)) {
                return Set.of();
            }
            Set<New> pts = varPointsTo.get(base);
            if (pts != null && !pts.isEmpty()) {
                return pts;
            }
            Set<New> res = new HashSet<>();
            for (New obj : allAllocs) {
                if (typeCompatible(obj, base.getType())) {
                    res.add(obj);
                }
            }
            return res;
        }

        private Set<New> getArrayPoints(New baseObj, Var indexVar) {
            Set<New> pts = new HashSet<>();
            ArrayIndex key = ArrayIndex.fromIndexVar(indexVar);
            if (!key.isAny()) {
                pts.addAll(getFieldPoints(new FieldSlot(baseObj, key)));
            }
            // Always include summary slot written by imprecise stores.
            pts.addAll(getFieldPoints(new FieldSlot(baseObj, ArrayIndex.any())));
            if (key.isAny()) {
                // Unknown index: include all precise slots of this array.
                fieldPointsTo.forEach((slot, values) -> {
                    if (slot.hasBase(baseObj) && slot.isArraySlot()) {
                        pts.addAll(values);
                    }
                });
            }
            return pts;
        }

        private Set<New> filterByType(Set<New> candidates, Type targetType) {
            if (!(targetType instanceof ReferenceType)) {
                return Set.of();
            }
            Set<New> filtered = new HashSet<>();
            for (New obj : candidates) {
                if (typeCompatible(obj, targetType)) {
                    filtered.add(obj);
                }
            }
            return filtered;
        }

        private boolean typeCompatible(New obj, Type targetType) {
            Type objType = obj.getRValue().getType();
            if (!(objType instanceof ReferenceType) || !(targetType instanceof ReferenceType)) {
                return false;
            }
            return typeSystem.isSubtype(targetType, objType);
        }

        private boolean addVarPoints(Var var, New obj) {
            if (!isReference(var)) {
                return false;
            }
            return varPointsTo.computeIfAbsent(var, k -> new HashSet<>())
                    .add(obj);
        }

        private <K> boolean addAll(Map<K, Set<New>> map,
                                   K key,
                                   Set<New> values) {
            if (values.isEmpty()) {
                return false;
            }
            return map.computeIfAbsent(key, k -> new HashSet<>())
                    .addAll(values);
        }

        private boolean addFieldPoints(FieldSlot slot, Set<New> values) {
            if (values.isEmpty()) {
                return false;
            }
            return fieldPointsTo.computeIfAbsent(slot, k -> new HashSet<>())
                    .addAll(values);
        }

        private Set<New> getVarPoints(Var var) {
            return varPointsTo.computeIfAbsent(var, k -> new HashSet<>());
        }

        private Set<New> pointsOrAny(Var var) {
            if (!isReference(var)) {
                return Set.of();
            }
            Set<New> pts = varPointsTo.get(var);
            if (pts != null && !pts.isEmpty()) {
                return pts;
            }
            return allTypedAllocs(var.getType());
        }

        private Set<New> allTypedAllocs(Type type) {
            if (!(type instanceof ReferenceType)) {
                return Set.of();
            }
            return allAllocsByType.computeIfAbsent(type, t -> {
                Set<New> res = new HashSet<>();
                for (New obj : allAllocs) {
                    if (typeCompatible(obj, type)) {
                        res.add(obj);
                    }
                }
                return res;
            });
        }

        private Set<New> getFieldPoints(FieldSlot slot) {
            return fieldPointsTo.computeIfAbsent(slot, k -> new HashSet<>());
        }

        private boolean isReference(Var v) {
            return v != null && v.getType() instanceof ReferenceType;
        }
    }

    /**
     * Key for instance fields/array elements on a given allocation site.
     */
    private static class FieldSlot {
        private final New base;
        private final Object field;

        FieldSlot(New base, Object field) {
            this.base = base;
            this.field = field;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            FieldSlot other = (FieldSlot) o;
            return base.equals(other.base) && field.equals(other.field);
        }

        @Override
        public int hashCode() {
            return base.hashCode() * 31 + field.hashCode();
        }

        boolean hasBase(New newStmt) {
            return base.equals(newStmt);
        }

        boolean isArraySlot() {
            return field instanceof ArrayIndex;
        }
    }

    private static class ArrayIndex {
        private static final ArrayIndex ANY = new ArrayIndex(null);
        private final Integer constant;

        private ArrayIndex(Integer constant) {
            this.constant = constant;
        }

        static ArrayIndex any() {
            return ANY;
        }

        static ArrayIndex fromIndexVar(Var index) {
            if (index != null && index.isConst() && index.getConstValue() instanceof pascal.taie.ir.exp.IntLiteral lit) {
                return new ArrayIndex(lit.getNumber());
            }
            return ANY;
        }

        boolean isAny() {
            return this == ANY;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ArrayIndex that = (ArrayIndex) o;
            if (isAny() && that.isAny()) {
                return true;
            }
            return !isAny() && !that.isAny() && constant.equals(that.constant);
        }

        @Override
        public int hashCode() {
            return isAny() ? 0 : constant.hashCode();
        }

        @Override
        public String toString() {
            return isAny() ? "<array>" : "[" + constant + "]";
        }
    }
}
