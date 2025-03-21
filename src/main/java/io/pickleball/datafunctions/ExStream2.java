//package io.pickleball.datafunctions;
//
//import io.pickleball.valueresolution.ValueChecker;
//
//import java.util.*;
//import java.util.function.*;
//import java.util.stream.*;
//
//public class ExStream2<T> implements Stream<T> , ValueChecker {
//    private final Stream<ValWrapper> delegate;
//    private CheckMode checkMode = CheckMode.ANY;
//    private Predicate<ValWrapper> currentPredicate = ValWrapper::hasValue;
//
//    private enum CheckMode {
//        ANY,
//        NONE,
//        ALL
//    }
//
//    public ExStream2(Stream<T> delegate) {
//        this.delegate = delegate.map(ValWrapper::new);
//    }
//
//    public ExStream2(List<?> list) {
//        this.delegate = list.stream().map(ValWrapper::new);
//    }
//
//
//    @SafeVarargs
//    public static <T> ExStream2<T> of(T... values) {
//        return new ExStream2<>(Stream.of(values));
//    }
//
//    @Override
//    public boolean getBoolValue() {
//        return switch (checkMode) {
//            case ANY -> delegate.anyMatch(currentPredicate);
//            case NONE -> delegate.noneMatch(currentPredicate);
//            case ALL -> delegate.allMatch(currentPredicate);
//        };
//    }
//
//    public ExStream2<T> checkAny() {
//        this.checkMode = CheckMode.ANY;
//        return this;
//    }
//
//    public ExStream2<T> checkNone() {
//        this.checkMode = CheckMode.NONE;
//        return this;
//    }
//
//    public ExStream2<T> checkAll() {
//        this.checkMode = CheckMode.ALL;
//        return this;
//    }
//
//    public ExStream2<T> checkForValue() {
//        this.currentPredicate = ValWrapper::hasValue;
//        return this;
//    }
//
//    public ExStream2<T> checkForNoValue() {
//        this.currentPredicate = wrapper -> !wrapper.hasValue();
//        return this;
//    }
//
//    public ExStream2<T> checkIfTrue() {
//        this.currentPredicate = ValWrapper::getBoolValue;
//        return this;
//    }
//
//    public ExStream2<T> isFalse() {
//        this.currentPredicate = wrapper -> !wrapper.getBoolValue();
//        return this;
//    }
//
//    @SuppressWarnings("unchecked")
//    @Override
//    public ExStream2<T> filter(Predicate<? super T> predicate) {
//        return (ExStream2<T>) new ExStream2<>(delegate.filter(wrapper ->
//                predicate.test((T) wrapper.getOriginalType().cast(wrapper))));
//    }
//
//    @SuppressWarnings("unchecked")
//    @Override
//    public <R> ExStream2<R> map(Function<? super T, ? extends R> mapper) {
//        return (ExStream2<R>) new ExStream2<>(delegate.map(wrapper ->
//                new ValWrapper(mapper.apply((T) wrapper.getOriginalType().cast(wrapper)))));
//    }
//
//    @SuppressWarnings("unchecked")
//    @Override
//    public IntStream mapToInt(ToIntFunction<? super T> mapper) {
//        return delegate.mapToInt(wrapper ->
//                mapper.applyAsInt((T) wrapper.getOriginalType().cast(wrapper)));
//    }
//
//    @SuppressWarnings("unchecked")
//    @Override
//    public LongStream mapToLong(ToLongFunction<? super T> mapper) {
//        return delegate.mapToLong(wrapper ->
//                mapper.applyAsLong((T) wrapper.getOriginalType().cast(wrapper)));
//    }
//
//    @SuppressWarnings("unchecked")
//    @Override
//    public DoubleStream mapToDouble(ToDoubleFunction<? super T> mapper) {
//        return delegate.mapToDouble(wrapper ->
//                mapper.applyAsDouble((T) wrapper.getOriginalType().cast(wrapper)));
//    }
//
//    @SuppressWarnings("unchecked")
//    @Override
//    public <R> ExStream2<R> flatMap(Function<? super T, ? extends Stream<? extends R>> mapper) {
//        return new ExStream2<>(delegate.flatMap(wrapper ->
//                mapper.apply((T) wrapper.getOriginalType().cast(wrapper))));
//    }
//
//    @SuppressWarnings("unchecked")
//    @Override
//    public IntStream flatMapToInt(Function<? super T, ? extends IntStream> mapper) {
//        return delegate.flatMapToInt(wrapper ->
//                mapper.apply((T) wrapper.getOriginalType().cast(wrapper)));
//    }
//
//    @SuppressWarnings("unchecked")
//    @Override
//    public LongStream flatMapToLong(Function<? super T, ? extends LongStream> mapper) {
//        return delegate.flatMapToLong(wrapper ->
//                mapper.apply((T) wrapper.getOriginalType().cast(wrapper)));
//    }
//
//    @SuppressWarnings("unchecked")
//    @Override
//    public DoubleStream flatMapToDouble(Function<? super T, ? extends DoubleStream> mapper) {
//        return delegate.flatMapToDouble(wrapper ->
//                mapper.apply((T) wrapper.getOriginalType().cast(wrapper)));
//    }
//
//    @SuppressWarnings("unchecked")
//    @Override
//    public ExStream2<T> distinct() {
//        return new ExStream2<>(delegate.distinct()
//                .map(wrapper -> (T) wrapper.getOriginalType().cast(wrapper)));
//    }
//
//    @SuppressWarnings("unchecked")
//    @Override
//    public ExStream2<T> sorted() {
//        return new ExStream2<>(delegate.sorted((w1, w2) ->
//                        w1.getNormalized().compareTo(w2.getNormalized()))
//                .map(wrapper -> (T) wrapper.getOriginalType().cast(wrapper)));
//    }
//
//    @SuppressWarnings("unchecked")
//    @Override
//    public ExStream2<T> sorted(Comparator<? super T> comparator) {
//        return new ExStream2<>(delegate.map(wrapper -> (T) wrapper.getOriginalType().cast(wrapper))
//                .sorted(comparator));
//    }
//
//    @SuppressWarnings("unchecked")
//    @Override
//    public ExStream2<T> peek(Consumer<? super T> action) {
//        return (ExStream2<T>) new ExStream2<>(delegate.peek(wrapper ->
//                action.accept((T) wrapper.getOriginalType().cast(wrapper))));
//    }
//
//    @SuppressWarnings("unchecked")
//    @Override
//    public ExStream2<T> limit(long maxSize) {
//        return new ExStream2<>(delegate.limit(maxSize)
//                .map(wrapper -> (T) wrapper.getOriginalType().cast(wrapper)));
//    }
//
//    @SuppressWarnings("unchecked")
//    @Override
//    public ExStream2<T> skip(long n) {
//        return new ExStream2<>(delegate.skip(n)
//                .map(wrapper -> (T) wrapper.getOriginalType().cast(wrapper)));
//    }
//
//    @SuppressWarnings("unchecked")
//    @Override
//    public void forEach(Consumer<? super T> action) {
//        delegate.forEach(wrapper -> action.accept((T) wrapper.getOriginalType().cast(wrapper)));
//    }
//
//    @SuppressWarnings("unchecked")
//    @Override
//    public void forEachOrdered(Consumer<? super T> action) {
//        delegate.forEachOrdered(wrapper ->
//                action.accept((T) wrapper.getOriginalType().cast(wrapper)));
//    }
//
//    @SuppressWarnings("unchecked")
//    @Override
//    public Object[] toArray() {
//        return delegate.map(wrapper -> (T) wrapper.getOriginalType().cast(wrapper))
//                .toArray();
//    }
//
//    @SuppressWarnings("unchecked")
//    @Override
//    public <A> A[] toArray(IntFunction<A[]> generator) {
//        return delegate.map(wrapper -> (T) wrapper.getOriginalType().cast(wrapper))
//                .toArray(generator);
//    }
//
//    @SuppressWarnings("unchecked")
//    @Override
//    public T reduce(T identity, BinaryOperator<T> accumulator) {
//        return delegate.map(wrapper -> (T) wrapper.getOriginalType().cast(wrapper))
//                .reduce(identity, accumulator);
//    }
//
//    @SuppressWarnings("unchecked")
//    @Override
//    public Optional<T> reduce(BinaryOperator<T> accumulator) {
//        return delegate.map(wrapper -> (T) wrapper.getOriginalType().cast(wrapper))
//                .reduce(accumulator);
//    }
//
//    @SuppressWarnings("unchecked")
//    @Override
//    public <U> U reduce(U identity, BiFunction<U, ? super T, U> accumulator, BinaryOperator<U> combiner) {
//        return delegate.map(wrapper -> (T) wrapper.getOriginalType().cast(wrapper))
//                .reduce(identity, accumulator, combiner);
//    }
//
//    @SuppressWarnings("unchecked")
//    @Override
//    public <R> R collect(Supplier<R> supplier, BiConsumer<R, ? super T> accumulator, BiConsumer<R, R> combiner) {
//        return delegate.map(wrapper -> (T) wrapper.getOriginalType().cast(wrapper))
//                .collect(supplier, accumulator, combiner);
//    }
//
//    @SuppressWarnings("unchecked")
//    @Override
//    public <R, A> R collect(Collector<? super T, A, R> collector) {
//        return delegate.map(wrapper -> (T) wrapper.getOriginalType().cast(wrapper))
//                .collect(collector);
//    }
//
//    @SuppressWarnings("unchecked")
//    @Override
//    public Optional<T> min(Comparator<? super T> comparator) {
//        return delegate.map(wrapper -> (T) wrapper.getOriginalType().cast(wrapper))
//                .min(comparator);
//    }
//
//    @SuppressWarnings("unchecked")
//    @Override
//    public Optional<T> max(Comparator<? super T> comparator) {
//        return delegate.map(wrapper -> (T) wrapper.getOriginalType().cast(wrapper))
//                .max(comparator);
//    }
//
//    @Override
//    public long count() {
//        return delegate.count();
//    }
//
//    @SuppressWarnings("unchecked")
//    @Override
//    public boolean anyMatch(Predicate<? super T> predicate) {
//        return delegate.map(wrapper -> (T) wrapper.getOriginalType().cast(wrapper))
//                .anyMatch(predicate);
//    }
//
//    @SuppressWarnings("unchecked")
//    @Override
//    public boolean allMatch(Predicate<? super T> predicate) {
//        return delegate.map(wrapper -> (T) wrapper.getOriginalType().cast(wrapper))
//                .allMatch(predicate);
//    }
//
//    @SuppressWarnings("unchecked")
//    @Override
//    public boolean noneMatch(Predicate<? super T> predicate) {
//        return delegate.map(wrapper -> (T) wrapper.getOriginalType().cast(wrapper))
//                .noneMatch(predicate);
//    }
//
//    @SuppressWarnings("unchecked")
//    @Override
//    public Optional<T> findFirst() {
//        return delegate.findFirst()
//                .map(wrapper -> (T) wrapper.getOriginalType().cast(wrapper));
//    }
//
//    @SuppressWarnings("unchecked")
//    @Override
//    public Optional<T> findAny() {
//        return delegate.findAny()
//                .map(wrapper -> (T) wrapper.getOriginalType().cast(wrapper));
//    }
//
//    @SuppressWarnings("unchecked")
//    @Override
//    public Iterator<T> iterator() {
//        return delegate.map(wrapper -> (T) wrapper.getOriginalType().cast(wrapper))
//                .iterator();
//    }
//
//    @SuppressWarnings("unchecked")
//    @Override
//    public Spliterator<T> spliterator() {
//        return delegate.map(wrapper -> (T) wrapper.getOriginalType().cast(wrapper))
//                .spliterator();
//    }
//
//    @Override
//    public boolean isParallel() {
//        return delegate.isParallel();
//    }
//
//    @SuppressWarnings("unchecked")
//    @Override
//    public ExStream2<T> sequential() {
//        return new ExStream2<>(delegate.sequential()
//                .map(wrapper -> (T) wrapper.getOriginalType().cast(wrapper)));
//    }
//
//    @SuppressWarnings("unchecked")
//    @Override
//    public ExStream2<T> parallel() {
//        return new ExStream2<>(delegate.parallel()
//                .map(wrapper -> (T) wrapper.getOriginalType().cast(wrapper)));
//    }
//
//    @SuppressWarnings("unchecked")
//    @Override
//    public ExStream2<T> unordered() {
//        return new ExStream2<>(delegate.unordered()
//                .map(wrapper -> (T) wrapper.getOriginalType().cast(wrapper)));
//    }
//
//    @SuppressWarnings("unchecked")
//    @Override
//    public ExStream2<T> onClose(Runnable closeHandler) {
//        return new ExStream2<>(delegate.onClose(closeHandler)
//                .map(wrapper -> (T) wrapper.getOriginalType().cast(wrapper)));
//    }
//
//    @Override
//    public void close() {
//        delegate.close();
//    }
//
//    public static void main(String[] args) {
//        ExStream2<Object> stream1 = ExStream2.of("true", null, "yes");
//        System.out.println(stream1.getBoolValue()); // true
//
//        ExStream2<Object> stream2 = ExStream2.of("true", "yes", "1");
//        System.out.println(stream2.checkAll()
//                .checkIfTrue()
//                .getBoolValue()); // true
//
//        ExStream2<Object> stream3 = ExStream2.of("a", null, 123);
//        System.out.println(stream3.checkNone()
//                .checkForValue()
//                .getBoolValue()); // false
//    }
//}