package tools.ds.modkit.attach;

import com.sun.tools.attach.VirtualMachine;

public final class ExternalAttacher {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: ExternalAttacher <pid> <agentJar>");
            System.exit(2);
        }
        String pid = args[0];
        String agentJar = args[1];
        VirtualMachine vm = null;
        try {
            vm = VirtualMachine.attach(pid);
            vm.loadAgent(agentJar, "");
            System.out.println("[modkit-helper] attached");
        } finally {
            if (vm != null) vm.detach();
        }
    }
    private ExternalAttacher() {}
}
