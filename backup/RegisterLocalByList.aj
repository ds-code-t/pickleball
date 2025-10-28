//package tools.dscode.registry;
//
//public aspect RegisterLocalByList {
//        /* Broad capture: constructor executions inside relevant Cucumber packages */
//        pointcut ctorInScope():
//                execution(io.cucumber.core.runtime..*.new(..))
//                        || execution(io.cucumber.core.feature..*.new(..))
//                        || execution(io.cucumber.java..*.new(..));
//
//        /* Allow-list by FQCN (loader-agnostic matching) */
//        private static final String[] TARGET_FQCNS = new String[] {
//                "io.cucumber.core.gherkin.Pickle"
//        };
//
//        private static boolean nameInAllowList(String fqcn) {
//            for (String s : TARGET_FQCNS) if (s.equals(fqcn)) return true;
//            return false;
//        }
//
//        /** Loader-agnostic “assignable” check (by FQCN name) */
//        private static boolean matchesByName(Object o) {
//            if (o == null) return false;
//            Class<?> c = o.getClass();
//            while (c != null) {
//                if (nameInAllowList(c.getName())) return true;
//                for (Class<?> itf : c.getInterfaces()) {
//                    if (nameInAllowList(itf.getName())) return true;
//                }
//                c = c.getSuperclass();
//            }
//            return false;
//        }
//
//        private static boolean looksAnonymousOrSynthetic(Class<?> c) {
//            String n = c.getName();
//            return n.contains("$$") || n.matches(".*\\$\\d+");
//        }
//
//        /* After successful constructor execution: fetch the instance and register */
//        after() returning(): ctorInScope() {
//            Object obj = thisJoinPoint.getThis();
//            if (obj == null) return;
//            if (looksAnonymousOrSynthetic(obj.getClass())) return;
//            if (!matchesByName(obj)) return;
//
//            GlobalRegistry.registerLocal(obj);
//        }
//    }
