;
; a trivial implementation of property list routines
; get, put, remprop	oz/91
; 

(define (assq k l)
  (cond [(null? l) #f]
        [(eq? (caar l) k) (car l)]
        [else (assq k (cdr l))]))
(define (member v l)
  (cond [(null? l) #f]
        [(eq? v (car l)) l]
        [else (member v (cdr l))]))

(define *namelist* '())      ; top-level name/plst chain 
(define *lastlook* '(xxx ())) ; look-aside cache

; locate the name/plst pair

(define (nameprop name)
  (if (eq? name (car *lastlook*))
      *lastlook*
      (let ((pair (assq name *namelist*)))
        (if pair
          (set! *lastlook* pair)
          (void))
        pair)))
(define (get name prop)
  (let ((r (nameprop name)))
    (if (pair? r)
        (let ((s (assq prop (cdr r))))
          (if (pair? s)
              (cdr s)
              #f))
        #f)))
(define (put name prop valu)
  (let ((r (nameprop name)))
    (if (pair? r)
        (let ((s (assq prop (cdr r))))
          (if (pair? s)
              (set-cdr! s valu)
              (let ((item (cons prop valu)))
                (set-cdr! r (cons item (cdr r))))))
        (let ((item (cons prop valu)))
          (set! *namelist* (cons (cons name (list item)) *namelist*)))))
  valu)
(define reinit-prop!
  (lambda ()
    (set! *namelist* '())
    (set! *lastlook* '(xxx ()))))

;;;
;;; You need to write a procedure named "run-benchmark" that takes
;;; two arguments.  The first is a string identifying the particular
;;; benchmark being run.  The second is a thunk, a procedure of no
;;; arguments, that will actually run the benchmark.
;;;


(define (run-benchmark benchmark-name benchmark-thunk)
  (let ((ten (lambda () 
               (benchmark-thunk)
               (benchmark-thunk)
               (benchmark-thunk)
               (benchmark-thunk)
               (benchmark-thunk)
               (benchmark-thunk)
               (benchmark-thunk)
               (benchmark-thunk)
               (benchmark-thunk)
               (benchmark-thunk))))
    (ten)
    (ten)
    (ten)
    (ten)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; File:         boyer.sch
; Description:  The Boyer benchmark
; Author:       Bob Boyer
; Created:      5-Apr-85
; Modified:     10-Apr-85 14:52:20 (Bob Shaw)
;               22-Jul-87 (Will Clinger)
; Language:     Scheme (but see note)
; Status:       Public Domain
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

; Note:  This benchmark uses property lists.  The procedures that must
; be supplied are get and put, where (put x y z) is equivalent to Common
; Lisp's (setf (get x y) z).
; Note:  The Common Lisp version of this benchmark returns the wrong
; answer because it uses the Common Lisp equivalent of memv instead of
; member in the falsep and truep procedures.  (The error arose because
; memv is called member in Common Lisp.  Don't ask what member is called,
; unless you want to learn about keyword arguments.)  This Scheme version
; may run a few percent slower than it would if it were equivalent to
; the Common Lisp version, but it works.

;;; BOYER -- Logic programming benchmark, originally written by Bob Boyer.
;;; Fairly CONS intensive.

(define (get-null name prop)
  (or (get name prop) '()))

(define unify-subst 0)
(define temp-temp 0)

(define (add-lemma term)
  (cond ((and (pair? term)
              (eq? (car term)
                   (quote equal))
              (pair? (cadr term)))
         (put (car (cadr term))
              (quote lemmas)
              (cons term (get-null (car (cadr term)) (quote lemmas)))))
        (else (error 'add-lemma "ADD-LEMMA did not like term:  " term))))

(define (add-lemma-lst lst)
  (cond ((null? lst)
         #t)
        (else (add-lemma (car lst))
              (add-lemma-lst (cdr lst)))))

(define (apply-subst alist term)
  (cond ((not (pair? term))
         (cond ((begin (set! temp-temp (assq term alist))
                       temp-temp)
                (cdr temp-temp))
               (else term)))
        (else (cons (car term)
                    (apply-subst-lst alist (cdr term))))))

(define (apply-subst-lst alist lst)
  (cond ((null? lst)
         '())
        (else (cons (apply-subst alist (car lst))
                    (apply-subst-lst alist (cdr lst))))))

(define (falsep x lst)
  (or (eq? x (quote (f)))
      (member x lst)))

(define (one-way-unify term1 term2)
  (begin (set! unify-subst '())
         (one-way-unify1 term1 term2)))

(define (one-way-unify1 term1 term2)
  (cond ((not (pair? term2))
         (cond ((begin (set! temp-temp (assq term2 unify-subst))
                       temp-temp)
                (eq? term1 (cdr temp-temp)))
               (else (set! unify-subst (cons (cons term2 term1)
                                             unify-subst))
                     #t)))
        ((not (pair? term1))
         #f)
        ((eq? (car term1)
              (car term2))
         (one-way-unify1-lst (cdr term1)
                             (cdr term2)))
        (else #f)))

(define (one-way-unify1-lst lst1 lst2)
  (cond ((null? lst1)
         #t)
        ((one-way-unify1 (car lst1)
                         (car lst2))
         (one-way-unify1-lst (cdr lst1)
                             (cdr lst2)))
        (else #f)))

(define (rewrite term)
  (cond ((not (pair? term))
         term)
        (else (rewrite-with-lemmas (cons (car term)
                                         (rewrite-args (cdr term)))
                                   (get-null (car term)
                                        (quote lemmas))))))

(define (rewrite-args lst)
  (cond ((null? lst)
         '())
        (else (cons (rewrite (car lst))
                    (rewrite-args (cdr lst))))))

(define (rewrite-with-lemmas term lst)
  (cond ((null? lst)
         term)
        ((one-way-unify term (cadr (car lst)))
         (rewrite (apply-subst unify-subst (caddr (car lst)))))
        (else (rewrite-with-lemmas term (cdr lst)))))

(define (setup)
  (add-lemma-lst
   (quote ((equal (compile form)
                  (reverse (codegen (optimize form)
                                    (nil))))
           (equal (eqp x y)
                  (equal (fix x)
                         (fix y)))
           (equal (greaterp x y)
                  (lessp y x))
           (equal (lesseqp x y)
                  (not (lessp y x)))
           (equal (greatereqp x y)
                  (not (lessp x y)))
           (equal (boolean x)
                  (or (equal x (t))
                      (equal x (f))))
           (equal (iff x y)
                  (and (implies x y)
                       (implies y x)))
           (equal (even1 x)
                  (if (zerop x)
                      (t)
                      (odd (1- x))))
           (equal (countps- l pred)
                  (countps-loop l pred (zero)))
           (equal (fact- i)
                  (fact-loop i 1))
           (equal (reverse- x)
                  (reverse-loop x (nil)))
           (equal (divides x y)
                  (zerop (remainder y x)))
           (equal (assume-true var alist)
                  (cons (cons var (t))
                        alist))
           (equal (assume-false var alist)
                  (cons (cons var (f))
                        alist))
           (equal (tautology-checker x)
                  (tautologyp (normalize x)
                              (nil)))
           (equal (falsify x)
                  (falsify1 (normalize x)
                            (nil)))
           (equal (prime x)
                  (and (not (zerop x))
                       (not (equal x (add1 (zero))))
                       (prime1 x (1- x))))
           (equal (and p q)
                  (if p (if q (t)
                              (f))
                        (f)))
           (equal (or p q)
                  (if p (t)
                        (if q (t)
                              (f))
                        (f)))
           (equal (not p)
                  (if p (f)
                        (t)))
           (equal (implies p q)
                  (if p (if q (t)
                              (f))
                        (t)))
           (equal (fix x)
                  (if (numberp x)
                      x
                      (zero)))
           (equal (if (if a b c)
                      d e)
                  (if a (if b d e)
                        (if c d e)))
           (equal (zerop x)
                  (or (equal x (zero))
                      (not (numberp x))))
           (equal (plus (plus x y)
                        z)
                  (plus x (plus y z)))
           (equal (equal (plus a b)
                         (zero))
                  (and (zerop a)
                       (zerop b)))
           (equal (difference x x)
                  (zero))
           (equal (equal (plus a b)
                         (plus a c))
                  (equal (fix b)
                         (fix c)))
           (equal (equal (zero)
                         (difference x y))
                  (not (lessp y x)))
           (equal (equal x (difference x y))
                  (and (numberp x)
                       (or (equal x (zero))
                           (zerop y))))
           (equal (meaning (plus-tree (append x y))
                           a)
                  (plus (meaning (plus-tree x)
                                 a)
                        (meaning (plus-tree y)
                                 a)))
           (equal (meaning (plus-tree (plus-fringe x))
                           a)
                  (fix (meaning x a)))
           (equal (append (append x y)
                          z)
                  (append x (append y z)))
           (equal (reverse (append a b))
                  (append (reverse b)
                          (reverse a)))
           (equal (times x (plus y z))
                  (plus (times x y)
                        (times x z)))
           (equal (times (times x y)
                         z)
                  (times x (times y z)))
           (equal (equal (times x y)
                         (zero))
                  (or (zerop x)
                      (zerop y)))
           (equal (exec (append x y)
                        pds envrn)
                  (exec y (exec x pds envrn)
                          envrn))
           (equal (mc-flatten x y)
                  (append (flatten x)
                          y))
           (equal (member x (append a b))
                  (or (member x a)
                      (member x b)))
           (equal (member x (reverse y))
                  (member x y))
           (equal (length (reverse x))
                  (length x))
           (equal (member a (intersect b c))
                  (and (member a b)
                       (member a c)))
           (equal (nth (zero)
                       i)
                  (zero))
           (equal (exp i (plus j k))
                  (times (exp i j)
                         (exp i k)))
           (equal (exp i (times j k))
                  (exp (exp i j)
                       k))
           (equal (reverse-loop x y)
                  (append (reverse x)
                          y))
           (equal (reverse-loop x (nil))
                  (reverse x))
           (equal (count-list z (sort-lp x y))
                  (plus (count-list z x)
                        (count-list z y)))
           (equal (equal (append a b)
                         (append a c))
                  (equal b c))
           (equal (plus (remainder x y)
                        (times y (quotient x y)))
                  (fix x))
           (equal (power-eval (big-plus1 l i base)
                              base)
                  (plus (power-eval l base)
                        i))
           (equal (power-eval (big-plus x y i base)
                              base)
                  (plus i (plus (power-eval x base)
                                (power-eval y base))))
           (equal (remainder y 1)
                  (zero))
           (equal (lessp (remainder x y)
                         y)
                  (not (zerop y)))
           (equal (remainder x x)
                  (zero))
           (equal (lessp (quotient i j)
                         i)
                  (and (not (zerop i))
                       (or (zerop j)
                           (not (equal j 1)))))
           (equal (lessp (remainder x y)
                         x)
                  (and (not (zerop y))
                       (not (zerop x))
                       (not (lessp x y))))
           (equal (power-eval (power-rep i base)
                              base)
                  (fix i))
           (equal (power-eval (big-plus (power-rep i base)
                                        (power-rep j base)
                                        (zero)
                                        base)
                              base)
                  (plus i j))
           (equal (gcd x y)
                  (gcd y x))
           (equal (nth (append a b)
                       i)
                  (append (nth a i)
                          (nth b (difference i (length a)))))
           (equal (difference (plus x y)
                              x)
                  (fix y))
           (equal (difference (plus y x)
                              x)
                  (fix y))
           (equal (difference (plus x y)
                              (plus x z))
                  (difference y z))
           (equal (times x (difference c w))
                  (difference (times c x)
                              (times w x)))
           (equal (remainder (times x z)
                             z)
                  (zero))
           (equal (difference (plus b (plus a c))
                              a)
                  (plus b c))
           (equal (difference (add1 (plus y z))
                              z)
                  (add1 y))
           (equal (lessp (plus x y)
                         (plus x z))
                  (lessp y z))
           (equal (lessp (times x z)
                         (times y z))
                  (and (not (zerop z))
                       (lessp x y)))
           (equal (lessp y (plus x y))
                  (not (zerop x)))
           (equal (gcd (times x z)
                       (times y z))
                  (times z (gcd x y)))
           (equal (value (normalize x)
                         a)
                  (value x a))
           (equal (equal (flatten x)
                         (cons y (nil)))
                  (and (nlistp x)
                       (equal x y)))
           (equal (listp (gopher x))
                  (listp x))
           (equal (samefringe x y)
                  (equal (flatten x)
                         (flatten y)))
           (equal (equal (greatest-factor x y)
                         (zero))
                  (and (or (zerop y)
                           (equal y 1))
                       (equal x (zero))))
           (equal (equal (greatest-factor x y)
                         1)
                  (equal x 1))
           (equal (numberp (greatest-factor x y))
                  (not (and (or (zerop y)
                                (equal y 1))
                            (not (numberp x)))))
           (equal (times-list (append x y))
                  (times (times-list x)
                         (times-list y)))
           (equal (prime-list (append x y))
                  (and (prime-list x)
                       (prime-list y)))
           (equal (equal z (times w z))
                  (and (numberp z)
                       (or (equal z (zero))
                           (equal w 1))))
           (equal (greatereqpr x y)
                  (not (lessp x y)))
           (equal (equal x (times x y))
                  (or (equal x (zero))
                      (and (numberp x)
                           (equal y 1))))
           (equal (remainder (times y x)
                             y)
                  (zero))
           (equal (equal (times a b)
                         1)
                  (and (not (equal a (zero)))
                       (not (equal b (zero)))
                       (numberp a)
                       (numberp b)
                       (equal (1- a)
                              (zero))
                       (equal (1- b)
                              (zero))))
           (equal (lessp (length (delete x l))
                         (length l))
                  (member x l))
           (equal (sort2 (delete x l))
                  (delete x (sort2 l)))
           (equal (dsort x)
                  (sort2 x))
           (equal (length (cons x1
                                (cons x2
                                      (cons x3 (cons x4
                                                     (cons x5
                                                           (cons x6 x7)))))))
                  (plus 6 (length x7)))
           (equal (difference (add1 (add1 x))
                              2)
                  (fix x))
           (equal (quotient (plus x (plus x y))
                            2)
                  (plus x (quotient y 2)))
           (equal (sigma (zero)
                         i)
                  (quotient (times i (add1 i))
                            2))
           (equal (plus x (add1 y))
                  (if (numberp y)
                      (add1 (plus x y))
                      (add1 x)))
           (equal (equal (difference x y)
                         (difference z y))
                  (if (lessp x y)
                      (not (lessp y z))
                      (if (lessp z y)
                          (not (lessp y x))
                          (equal (fix x)
                                 (fix z)))))
           (equal (meaning (plus-tree (delete x y))
                           a)
                  (if (member x y)
                      (difference (meaning (plus-tree y)
                                           a)
                                  (meaning x a))
                      (meaning (plus-tree y)
                               a)))
           (equal (times x (add1 y))
                  (if (numberp y)
                      (plus x (times x y))
                      (fix x)))
           (equal (nth (nil)
                       i)
                  (if (zerop i)
                      (nil)
                      (zero)))
           (equal (last (append a b))
                  (if (listp b)
                      (last b)
                      (if (listp a)
                          (cons (car (last a))
                                b)
                          b)))
           (equal (equal (lessp x y)
                         z)
                  (if (lessp x y)
                      (equal t z)
                      (equal f z)))
           (equal (assignment x (append a b))
                  (if (assignedp x a)
                      (assignment x a)
                      (assignment x b)))
           (equal (car (gopher x))
                  (if (listp x)
                      (car (flatten x))
                      (zero)))
           (equal (flatten (cdr (gopher x)))
                  (if (listp x)
                      (cdr (flatten x))
                      (cons (zero)
                            (nil))))
           (equal (quotient (times y x)
                            y)
                  (if (zerop y)
                      (zero)
                      (fix x)))
           (equal (get j (set i val mem))
                  (if (eqp j i)
                      val
                      (get j mem)))))))

(define (tautologyp x true-lst false-lst)
  (cond ((truep x true-lst)
         #t)
        ((falsep x false-lst)
         #f)
        ((not (pair? x))
         #f)
        ((eq? (car x)
              (quote if))
         (cond ((truep (cadr x)
                       true-lst)
                (tautologyp (caddr x)
                            true-lst false-lst))
               ((falsep (cadr x)
                        false-lst)
                (tautologyp (cadddr x)
                            true-lst false-lst))
               (else (and (tautologyp (caddr x)
                                      (cons (cadr x)
                                            true-lst)
                                      false-lst)
                          (tautologyp (cadddr x)
                                      true-lst
                                      (cons (cadr x)
                                            false-lst))))))
        (else #f)))

(define (tautp x)
  (tautologyp (rewrite x)
              '() '()))

(define (test)
  (define ans #f)
  (define term #f)
  (set! term
        (apply-subst
         (quote ((x f (plus (plus a b)
                            (plus c (zero))))
                 (y f (times (times a b)
                             (plus c d)))
                 (z f (reverse (append (append a b)
                                       (nil))))
                 (u equal (plus a b)
                          (difference x y))
                 (w lessp (remainder a b)
                          (member a (length b)))))
         (quote (implies (and (implies x y)
                              (and (implies y z)
                                   (and (implies z u)
                                        (implies u w))))
                         (implies x w)))))
  (set! ans (tautp term))
  ans)

(define (trans-of-implies n)
  (list (quote implies)
        (trans-of-implies1 n)
        (list (quote implies)
              0 n)))

(define (trans-of-implies1 n)
  (cond ((eq? n 1)
         (list (quote implies)
               0 1))
        (else (list (quote and)
                    (list (quote implies)
                          (- n 1)
                          n)
                    (trans-of-implies1 (- n 1))))))

(define (truep x lst)
  (or (eq? x (quote (t)))
      (member x lst)))

;;; make sure you've run (setup) then call:  (test)

(run-benchmark "Boyer" (lambda () (setup) (test)))
