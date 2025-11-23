package pku;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import pascal.taie.ir.IR;
import pascal.taie.ir.exp.IntLiteral;
import pascal.taie.ir.exp.InvokeStatic;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.New;

import java.util.Map;

public class PreprocessResult {

    public final Map<New, Set<Integer>> obj_ids;
    public final Map<Integer, Set<Var>> test_pts;

    public PreprocessResult() {
        obj_ids = new HashMap<>();
        test_pts = new HashMap<>();
    }

    /**
     * Benchmark.alloc(id);
     * X x = new X;// stmt
     * 
     * @param stmt statement that allocates a new object
     * @param id   id of the object allocated
     */
    public void alloc(New stmt, int id) {
        obj_ids.computeIfAbsent(stmt, k -> new HashSet<>()).add(id);
    }

    /**
     * Benchmark.test(id, var)
     * 
     * @param id id of the testing
     * @param v  the pointer/variable
     */
    public void test(int id, Var v) {
        test_pts.computeIfAbsent(id, k -> new HashSet<>()).add(v);
    }

    /**
     *
     * @param stmt statement that allocates a new object
     * @return id of the object allocated
     */
    public Set<Integer> getObjIdAt(New stmt) {
        return obj_ids.getOrDefault(stmt, Set.of());
    }

    /**
     * @param id
     * @return the pointers/variables in Benchmark.test(id, var);
     */
    public Set<Var> getTestPt(int id) {
        return test_pts.getOrDefault(id, Set.of());
    }

    /**
     * analysis of a JMethod, the result storing in this
     * 
     * @param ir ir of a JMethod
     */
    public void analysis(IR ir) {
        var stmts = ir.getStmts();
        Deque<Integer> pendingAllocs = new ArrayDeque<>();
        for (var stmt : stmts) {

            if (stmt instanceof Invoke) {
                var exp = ((Invoke) stmt).getInvokeExp();
                if (exp instanceof InvokeStatic) {
                    var methodRef = ((InvokeStatic) exp).getMethodRef();
                    var className = methodRef.getDeclaringClass().getName();
                    var methodName = methodRef.getName();
                    if (className.equals("benchmark.internal.Benchmark")
                            || className.equals("benchmark.internal.BenchmarkN")) {
                        if (methodName.equals("alloc")) {
                            var lit = exp.getArg(0).getConstValue();
                            assert lit instanceof IntLiteral;
                            pendingAllocs.addLast(((IntLiteral) lit).getNumber());
                        } else if (methodName.equals("test")) {
                            var lit = exp.getArg(0).getConstValue();
                            assert lit instanceof IntLiteral;
                            var test_id = ((IntLiteral) lit).getNumber();
                            var pt = exp.getArg(1);
                            this.test(test_id, pt);
                        }
                    }

                }
            } else if (stmt instanceof New) {
                if (!pendingAllocs.isEmpty()) {
                    var n = (New) stmt;
                    pendingAllocs.forEach(id -> this.alloc(n, id));
                    pendingAllocs.clear();
                }
            }
        }
    }
}
