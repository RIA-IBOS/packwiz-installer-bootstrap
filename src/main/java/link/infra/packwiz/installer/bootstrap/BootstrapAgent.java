package link.infra.packwiz.installer.bootstrap;

import java.lang.instrument.Instrumentation;

public final class BootstrapAgent {
	private BootstrapAgent() {}

	public static void premain(String agentArgs, Instrumentation inst) {
		start(agentArgs);
	}

	public static void premain(String agentArgs) {
		start(agentArgs);
	}

	private static void start(String agentArgs) {
		String[] args = parseAgentArgs(agentArgs);
		if (args.length == 0) {
			System.out.println("[" + BootstrapInfo.DISPLAY_NAME + "] No agent arguments provided; skipping bootstrap.");
			return;
		}
		System.out.println("[" + BootstrapInfo.DISPLAY_NAME + "] Starting bootstrap as javaagent.");
		Bootstrap.init(args);
	}

	private static String[] parseAgentArgs(String agentArgs) {
		if (agentArgs == null) {
			return new String[0];
		}
		String trimmed = agentArgs.trim();
		if (trimmed.isEmpty()) {
			return new String[0];
		}
		if (trimmed.indexOf(' ') >= 0 || trimmed.indexOf('\t') >= 0) {
			return trimmed.split("\\s+");
		}
		return new String[] { trimmed };
	}
}
