#include "deque.h"
#include "mockups.h"

static Deque *deque;
static DequeConf conf;
int stat;

void setup_tests() { stat = deque_new(&deque); }

void teardown_tests() { deque_destroy(deque); }

bool pred1(const void *e) { return *(int *)e <= 3; }

bool pred2(const void *e) { return *(int *)e > 3; }

bool pred3(const void *e) { return *(int *)e > 5; }

int main() {
    setup_tests();

    int a = sym_int("a");
    int b = sym_int("b");
    int c = sym_int("c");
    int d = sym_int("d");
    int e = sym_int("e");
    int f = sym_int("f");

    assume(pred2(&d) && pred2(&e) && pred2(&f) && !pred2(&a) && !pred2(&b) &&
           !pred2(&c));

    deque_add_last(deque, &a);
    deque_add_last(deque, &b);
    deque_add_last(deque, &c);
    deque_add_last(deque, &d);
    deque_add_last(deque, &e);
    deque_add_last(deque, &f);
    assert(6 == deque_size(deque));

    deque_filter_mut(deque, pred2);
    assert(3 == deque_size(deque));

    int *removed = NULL;
    deque_remove_first(deque, (void *)&removed);
    assert(d == *removed);

    deque_remove_first(deque, (void *)&removed);
    assert(e == *removed);

    deque_remove_first(deque, (void *)&removed);
    assert(f == *removed);

    teardown_tests();
    return 0;
}
