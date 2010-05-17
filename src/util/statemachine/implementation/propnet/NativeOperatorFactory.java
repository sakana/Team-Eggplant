package util.statemachine.implementation.propnet;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import util.propnet.architecture.Component;
import util.propnet.architecture.components.And;
import util.propnet.architecture.components.Constant;
import util.propnet.architecture.components.Not;
import util.propnet.architecture.components.Or;
import util.propnet.architecture.components.Proposition;
import util.propnet.architecture.components.Transition;

public class NativeOperatorFactory {
	private static final String GEN_DIR = "gen";
	private static String fileName = "NativeOperator";
	private static final String path = GEN_DIR + File.separator + fileName + ".c";
	private static final String HEADER_NAME = "util_statemachine_implementation_propnet_NativeOperator.h";

	private static final String PREFIX = "Java_util_statemachine_implementation_propnet_NativeOperator_";
	private static final String PROPAGATE = "propagate";
	private static final String TRANSITION = "transition";
	private static final String INTERNAL = "propagateInternal";
	private static final String TERMINAL = "propagateTerminalOnly";
	private static final String LEGAL = "propagateLegalOnly";
	private static final String GOAL = "propagateGoalOnly";

	private static int classCount = 0;
	private static int constantProps = 0;
	private static int internalProps = 0;

	public static Operator buildOperator(Map<Proposition, Integer> propMap, List<Proposition> transitionOrdering, List<Proposition> internalOrdering,
			List<Proposition> terminalOrdering, List<List<Proposition>> legalOrderings, List<List<Proposition>> goalOrderings) {
		StringBuilder source = new StringBuilder();
		
		addPrefix(source);

		addTransition(source, transitionOrdering, propMap);
		addInternalPropagate(source, internalOrdering, propMap);
		addPropagate(source);
		addTerminalPropagate(source, terminalOrdering, propMap);
		addLegalPropagate(source, legalOrderings, propMap);
		addGoalPropagate(source, goalOrderings, propMap);

		try {
			FileWriter writer = new FileWriter(path);
			writer.write(source.toString());
			writer.close();
			
			Runtime rt = Runtime.getRuntime();
			Process p = rt.exec("gcc -shared -fPIC -std=c99 -I/usr/lib/jvm/java-6-sun/include -I/usr/lib/jvm/java-6-sun/include/linux " +
					fileName + ".c -o lib" + fileName + ".so", null, new File(GEN_DIR));
			if (p.waitFor() == 0) {
				System.out.println("Compilation successful!");
			} else {
				System.err.println("Compilation error!");
			}
			
			return new NativeOperator();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return null;
	}
	
	private static void addPrefix(StringBuilder source) {
		source.append("#include <stdbool.h>\n");
		source.append("#include \"" + HEADER_NAME + "\"\n");
	}

	private static void addTransition(StringBuilder source, List<Proposition> transitionOrdering, Map<Proposition, Integer> propMap) {
		StringBuilder body = generateTransitionMethodBody(transitionOrdering, propMap);
		addMethod(source, body, TRANSITION);
		addWrapper(source, TRANSITION, false);
	}

	private static void addInternalPropagate(StringBuilder source, List<Proposition> internalOrdering, Map<Proposition, Integer> propMap) {
		StringBuilder body = generateInternalMethodBody(internalOrdering, propMap);
		addMethod(source, body, INTERNAL);
		addWrapper(source, INTERNAL, false);
	}

	private static void addPropagate(StringBuilder source) {
		StringBuilder body = new StringBuilder();
		body.append(INTERNAL + "(props);\n");
		body.append(TRANSITION + "(props);\n");
		addMethod(source, body, PROPAGATE);
		addWrapper(source, PROPAGATE, false);
	}

	private static void addTerminalPropagate(StringBuilder source, List<Proposition> terminalOrdering, Map<Proposition, Integer> propMap) {
		StringBuilder body = generateInternalMethodBody(terminalOrdering, propMap);
		addMethod(source, body, TERMINAL);
		addWrapper(source, TERMINAL, false);
	}

	private static void addLegalPropagate(StringBuilder source, List<List<Proposition>> legalOrderings, Map<Proposition, Integer> propMap) {
		addRoleDependentHelpers(legalOrderings, propMap, source, LEGAL);
		StringBuilder body = generateRoleDependentBody(LEGAL, legalOrderings.size());
		addRoleDependentMethod(source, body, LEGAL);
		addWrapper(source, LEGAL, true);
	}

	private static void addGoalPropagate(StringBuilder source, List<List<Proposition>> goalOrderings, Map<Proposition, Integer> propMap) {
		addRoleDependentHelpers(goalOrderings, propMap, source, GOAL);
		StringBuilder body = generateRoleDependentBody(GOAL, goalOrderings.size());
		addRoleDependentMethod(source, body, GOAL);
		addWrapper(source, GOAL, true);
	}
	
	private static void addWrapper(StringBuilder source, String methodName, boolean indexNeeded) {
		StringBuilder method = new StringBuilder();
		method.append("JNIEXPORT void JNICALL " + PREFIX + methodName + "(JNIEnv *env, jobject obj, jbooleanArray javaArray" +
				(indexNeeded ? ", jint roleIndex" : "") +
				") {\n");
//		method.append("jboolean copy = 0;\n");
//		method.append("jboolean *props = (*env)->GetPrimitiveArrayCritical(env, javaArray, &copy);\n");
		method.append("jboolean *props = (*env)->GetIntArrayElements(env, javaArray, NULL);;\n");
//		method.append("printf(\"copy: %d\\n\", copy);");
		method.append(methodName + "(props" + (indexNeeded ? ", roleIndex" : "") + ");\n");
		method.append("(*env)->ReleaseIntArrayElements(env, javaArray, props, 0);\n");
//		method.append("(*env)->ReleasePrimitiveArrayCritical(env, javaArray, props, 0);\n");
		method.append("}\n");

		source.append(method);
	}

	private static void addMethod(StringBuilder source, StringBuilder body, String methodName) {
		StringBuilder method = new StringBuilder();
		method.append("void " + methodName + "(jboolean *props) {\n");
		method.append(body);
		method.append("}\n");

		source.append(method);
	}

	private static void addRoleDependentMethod(StringBuilder source, StringBuilder body, String methodName) {
		StringBuilder method = new StringBuilder();
		method.append("void " + methodName + "(jboolean *props, jint roleIndex) {\n");
		method.append(body);
		method.append("}\n");
		// System.out.println(method);
		source.append(method);
	}

	private static StringBuilder generateInternalMethodBody(List<Proposition> ordering, Map<Proposition, Integer> propMap) {
		StringBuilder body = new StringBuilder();
		for (Proposition p : ordering) {
			addInternalComponent(p, body, propMap);
		}
		return body;
	}

	private static StringBuilder generateTransitionMethodBody(List<Proposition> ordering, Map<Proposition, Integer> propMap) {
		StringBuilder body = new StringBuilder();
		for (Proposition p : ordering) {
			addTransitionComponent(p, body, propMap);
		}
		return body;
	}

	private static StringBuilder generateRoleDependentBody(String name, int roles) {
		StringBuilder body = new StringBuilder();
		body.append("switch (roleIndex) {\n");
		for (int roleIndex = 0; roleIndex < roles; roleIndex++) {
			body.append("case " + roleIndex + ":\n");
			body.append(name + "Role" + roleIndex + "(props);\n");
			body.append("break;\n");
		}
		body.append("}\n");
		return body;
	}

	private static void addRoleDependentHelpers(List<List<Proposition>> orderings, Map<Proposition, Integer> propMap, StringBuilder source,
			String name) {
		for (int roleIndex = 0; roleIndex < orderings.size(); roleIndex++) {
			StringBuilder body = generateInternalMethodBody(orderings.get(roleIndex), propMap);
			addMethod(source, body, name + "Role" + roleIndex);
		}
	}

	private static void addInternalComponent(Proposition proposition, StringBuilder body, Map<Proposition, Integer> propMap) {
		Component comp = proposition.getSingleInput();
		int propositionIndex = propMap.get(proposition);
		internalProps++;
		if (comp instanceof Constant) {
			constantProps++;
			body.append("props[" + propositionIndex + "] = " + comp.getValue() + ";\n");
		} else if (comp instanceof Not) {
			if (!propMap.containsKey(comp.getSingleInput())) {
				body.append("props[" + propositionIndex + "] = !" + comp.getSingleInput().getValue() + ";\n");
			} else {
				body.append("props[" + propositionIndex + "] = !props[" + propMap.get(comp.getSingleInput()) + "];\n");
			}
		} else if (comp instanceof And) {
			Set<Component> connected = comp.getInputs();
			StringBuilder and = new StringBuilder();
			and.append("props[" + propositionIndex + "] = true");

			for (Component prop : connected) {
				if (!propMap.containsKey(prop)) {
					// if the proposition is not in the proposition map, it is
					// never changed: it is effectively a constant
					if (prop.getValue()) {
						continue;
					} else {
						and = new StringBuilder("props[" + propositionIndex + "] = false");
						break;
					}
				} else {
					and.append(" && props[" + propMap.get(prop) + "]");
				}
			}

			and.append(";\n");

			body.append(and);

		} else if (comp instanceof Or) {
			Set<Component> connected = comp.getInputs();
			StringBuilder or = new StringBuilder();
			or.append("props[" + propositionIndex + "] = false");

			for (Component prop : connected) {
				if (!propMap.containsKey(prop)) {
					// if the proposition is not in the proposition map, it is
					// never changed: it is effectively a constant
					if (prop.getValue()) {
						or = new StringBuilder("props[" + propositionIndex + "] = true");
						break;
					} else {
						continue;
					}
				} else {
					or.append(" || props[" + propMap.get(prop) + "]");
				}
			}

			or.append(";\n");

			body.append(or);

		} else {
			throw new RuntimeException("Unexpected Class");
		}
	}

	private static void addTransitionComponent(Proposition proposition, StringBuilder body, Map<Proposition, Integer> propMap) {
		int propositionIndex = propMap.get(proposition);
		Component comp = proposition.getSingleInput();
		if (comp instanceof Constant) {
			body.append("props[" + propositionIndex + "] = " + comp.getValue() + ";\n");
		} else if (comp instanceof Transition) {
			if (!propMap.containsKey(comp.getSingleInput())) {
				body.append("props[" + propositionIndex + "] = " + comp.getSingleInput().getValue() + ";\n");
			} else {
				body.append("props[" + propositionIndex + "] = props[" + propMap.get(comp.getSingleInput()) + "];\n");
			}
		} else {
			throw new RuntimeException("Unexpected Class");
		}
	}
}
