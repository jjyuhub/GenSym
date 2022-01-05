#ifndef LLSC_STP_HEADERS
#define LLSC_STP_HEADERS

#include <stp/c_interface.h>
#include <stp_handle.hpp>

inline VC global_vc = vc_createValidityChecker();

struct ExprHandle: public std::shared_ptr<void> {
  typedef std::shared_ptr<void> Base;

  static void freeExpr(Expr e) {
    vc_DeleteExpr(e);
  }

  ExprHandle(Expr e): Base(e, freeExpr) {}
};

inline std::unordered_map<PtrVal, std::pair<ExprHandle, std::set<ExprHandle>>> stp_env;
inline ExprHandle construct_STP_expr_internal(VC, PtrVal, std::set<ExprHandle>&);

using CacheKey = std::set<ExprHandle>;
using CexType = std::map<ExprHandle, IntData>;
using CacheResult = std::pair<int, CexType>;
inline std::map<CacheKey, CacheResult> cache_map;

inline ExprHandle construct_STP_expr(VC vc, PtrVal e, std::set<ExprHandle> &vars) {
  // search expr cache
  if (use_objcache) {
    auto it = stp_env.find(e);
    if (it != stp_env.end()) {
      auto &vars2 = it->second.second;
      vars.insert(vars2.begin(), vars2.end());
      return it->second.first;
    }
  }
  // query internal
  std::set<ExprHandle> vars2;
  auto ret = construct_STP_expr_internal(vc, e, vars2);
  vars.insert(vars2.begin(), vars2.end());
  // store expr cache
  if (use_objcache) {
    stp_env.emplace(e, std::make_pair(ret, std::move(vars2)));
  }
  return ret;
}

inline ExprHandle construct_STP_expr_internal(VC vc, PtrVal e, std::set<ExprHandle> &vars) {
  auto int_e = std::dynamic_pointer_cast<IntV>(e);
  if (int_e) {
    return vc_bvConstExprFromLL(vc, int_e->bw, int_e->i);
  }
  auto sym_e = std::dynamic_pointer_cast<SymV>(e);
  if (!sym_e) ABORT("Non-symbolic/integer value in path condition");

  if (!sym_e->name.empty()) {
    auto name = sym_e->name;
    ExprHandle stp_expr = vc_varExpr(vc, name.c_str(), vc_bvType(vc, sym_e->bw));
    vars.insert(stp_expr);
    return stp_expr;
  }

  std::vector<ExprHandle> expr_rands;
  int bw = sym_e->bw;
  for (auto e : sym_e->rands) {
    expr_rands.push_back(construct_STP_expr(vc, e, vars));
  }
  switch (sym_e->rator) {
    case op_add:
      return vc_bvPlusExpr(vc, bw, expr_rands.at(0).get(), expr_rands.at(1).get());
    case op_sub:
      return vc_bvMinusExpr(vc, bw, expr_rands.at(0).get(), expr_rands.at(1).get());
    case op_mul:
      return vc_bvMultExpr(vc, bw, expr_rands.at(0).get(), expr_rands.at(1).get());
    case op_sdiv:
    case op_udiv:
      return vc_bvDivExpr(vc, bw, expr_rands.at(0).get(), expr_rands.at(1).get());
    case op_uge:
      return vc_bvGeExpr(vc, expr_rands.at(0).get(), expr_rands.at(1).get());
    case op_sge:
      return vc_sbvGeExpr(vc, expr_rands.at(0).get(), expr_rands.at(1).get());
    case op_ugt:
      return vc_bvGtExpr(vc, expr_rands.at(0).get(), expr_rands.at(1).get());
    case op_sgt:
      return vc_sbvGtExpr(vc, expr_rands.at(0).get(), expr_rands.at(1).get());
    case op_ule:
      return vc_bvLeExpr(vc, expr_rands.at(0).get(), expr_rands.at(1).get());
    case op_sle:
      return vc_sbvLeExpr(vc, expr_rands.at(0).get(), expr_rands.at(1).get());
    case op_ult:
      return vc_bvLtExpr(vc, expr_rands.at(0).get(), expr_rands.at(1).get());
    case op_slt:
      return vc_sbvLtExpr(vc, expr_rands.at(0).get(), expr_rands.at(1).get());
    case op_eq:
      return vc_eqExpr(vc, expr_rands.at(0).get(), expr_rands.at(1).get());
    case op_neq:
      return vc_notExpr(vc, vc_eqExpr(vc, expr_rands.at(0).get(), expr_rands.at(1).get()));
    case op_neg:
      return vc_notExpr(vc, expr_rands.at(0).get());
    case op_sext:
      return vc_bvSignExtend(vc, expr_rands.at(0).get(), bw);
    case op_shl:
      return vc_bvLeftShiftExprExpr(vc, bw, expr_rands.at(0).get(), expr_rands.at(1).get());
    case op_lshr:
      return vc_bvRightShiftExprExpr(vc, bw, expr_rands.at(0).get(), expr_rands.at(1).get());
    case op_ashr:
      return vc_bvSignedRightShiftExprExpr(vc, bw, expr_rands.at(0).get(), expr_rands.at(1).get());
    case op_and:
      return vc_bvAndExpr(vc, expr_rands.at(0).get(), expr_rands.at(1).get());
    case op_or:
      return vc_bvOrExpr(vc, expr_rands.at(0).get(), expr_rands.at(1).get());
    case op_xor:
      return vc_bvXorExpr(vc, expr_rands.at(0).get(), expr_rands.at(1).get());
    case op_urem:
      return vc_bvRemExpr(vc, bw, expr_rands.at(0).get(), expr_rands.at(1).get());
    case op_srem:
      return vc_sbvRemExpr(vc, bw, expr_rands.at(0).get(), expr_rands.at(1).get());
    case op_trunc:
      // bvExtract(vc, e, h, l) -> e[l:h+1]
      return vc_bvExtract(vc, expr_rands.at(0).get(), bw-1, 0);
    default: break;
  }
  ABORT("unkown operator when constructing STP expr");
}

struct Checker {
  std::set<ExprHandle> variables;
  CexType *cex, cex2;
  VC vc;

  Checker() {
    if (use_global_solver) {
      vc = global_vc;
      vc_push(vc);
    } else {
      vc = vc_createValidityChecker();
    }
  }

  ~Checker() {
    variables.clear();
    cex2.clear();
    if (use_global_solver) {
      vc_pop(vc);
    } else {
      vc_Destroy(vc);
    }
  }

  void get_STP_counterexample(CexType &cex) {
    for (auto expr: variables) {
      auto val = vc_getCounterExample(vc, expr.get());
      cex[expr] = getBVUnsignedLongLong(val);
      vc_DeleteExpr(val);
    }
  }

  int make_query(PC pcobj) {
    CacheResult *result;
    auto pc = pcobj.getPC();
    auto last = pcobj.getLast();
    CacheKey pc2;
    // constraint independence
    if (use_cons_indep && last && pc.size() > 1) {
      std::map<ExprHandle, std::set<ExprHandle>> v2q, q2v;
      std::queue<ExprHandle> queue;
      for (auto &e: pc) {
        std::set<ExprHandle> vars;
        auto q = construct_STP_expr(vc, e, vars);
        if (e == last)
          queue.push(q);
        for (auto &v: vars)
          v2q[v].insert(q);
        q2v[q] = std::move(vars);
      }
      while (!queue.empty()) {
        auto q = queue.front(); queue.pop();
        auto &vars = q2v[q];
        if (!vars.empty()) {
          pc2.insert(q);
          variables.insert(vars.begin(), vars.end());
          for (auto &v: vars) {
            for (auto &q2: v2q[v])
              if (q2 != q)
                queue.push(q2);
            v2q[v].clear();
          }
          vars.clear();
        }
      }
    } else {
      for (auto& e: pc)
        pc2.insert(construct_STP_expr(vc, e, variables));
    }
    // cex cache: query
    if (use_cexcache) {
      auto ins = cache_map.emplace(pc2, CacheResult {});
      result = &(ins.first->second);
      cex = &(result->second);
      if (!ins.second) {
        cached_query_num++;
        return result->first;
      }
    }
    // actual solving
    auto start = steady_clock::now();
    for (auto &e: pc2)
      vc_assertFormula(vc, e.get());
    ExprHandle fls = vc_falseExpr(vc);
    int retcode = vc_query(vc, fls.get());
    auto end = steady_clock::now();
    solver_time += duration_cast<microseconds>(end - start);
    // cex cache: store
    if (use_cexcache) {
      result->first = retcode;
      if (retcode == 0)
        get_STP_counterexample(*cex);
    }
    return retcode;
  }

  const CexType* get_counterexample() {
    if (use_cexcache) return cex;
    get_STP_counterexample(cex2);
    return &cex2;
  }
};

// returns true if it is sat, otherwise false
// XXX: should explore paths with timeout/no-answer cond?
inline bool check_pc(PC pc) {
  if (!use_solver) return true;
  br_query_num++;
  Checker c;
  auto result = c.make_query(pc);
  return result == 0;
}

inline void check_pc_to_file(SS state) {
  if (!use_solver) {
    return;
  }
  Checker c;

  if (mkdir("tests", 0777) == -1) {
    if (errno == EEXIST) { }
    else {
      ABORT("Cannot create the folder tests, abort.\n");
    }
  }

  std::stringstream output;
  output << "Query number: " << (test_query_num+1) << std::endl;

  auto result = c.make_query(state.getPC());

  switch (result) {
  case 0:
    output << "Query is invalid" << std::endl;
    break;
  case 1:
    output << "Query is Valid" << std::endl;
    break;
  case 2:
    output << "Could not answer the query" << std::endl;
    break;
  case 3:
    output << "Timeout" << std::endl;
    break;
  }

  if (result == 0) {
    test_query_num++;
    std::stringstream filename;
    filename << "tests/" << test_query_num << ".test";
    int out_fd = open(filename.str().c_str(), O_RDWR | O_CREAT, 0777);
    if (out_fd == -1) {
        ABORT("Cannot create the test case file, abort.\n");
    }

    auto cex = c.get_counterexample();
    for (auto &kv: *cex) {
      output << exprName(kv.first.get()) << " == " << kv.second << std::endl;
    }
    int n = write(out_fd, output.str().c_str(), output.str().size());
    // vc_printCounterExampleFile(vc, out_fd);
    close(out_fd);
  }
}

#endif
