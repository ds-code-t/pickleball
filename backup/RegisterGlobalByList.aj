//package tools.dscode.registry;
//
//public aspect RegisterGlobalByList {
//    pointcut targets():
//          execution((com.example.FooService+).new(..))
//        || execution((com.example.BarManager+).new(..));
//
//    after() returning(Object obj): targets() && this(obj) {
//        GlobalRegistry.registerGlobal(obj);
//    }
//}
