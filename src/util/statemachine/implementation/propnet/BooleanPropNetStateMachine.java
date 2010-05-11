package util.statemachine.implementation.propnet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CodeConverter;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.CtNewMethod;
import javassist.NotFoundException;
import player.gamer.statemachine.eggplant.misc.Log;
import util.gdl.grammar.Gdl;
import util.gdl.grammar.GdlConstant;
import util.gdl.grammar.GdlProposition;
import util.gdl.grammar.GdlRelation;
import util.gdl.grammar.GdlSentence;
import util.gdl.grammar.GdlTerm;
import util.propnet.architecture.Component;
import util.propnet.architecture.PropNet;
import util.propnet.architecture.components.And;
import util.propnet.architecture.components.Constant;
import util.propnet.architecture.components.Not;
import util.propnet.architecture.components.Or;
import util.propnet.architecture.components.Proposition;
import util.propnet.architecture.components.Transition;
import util.propnet.factory.CachedPropNetFactory;
import util.statemachine.BooleanMachineState;
import util.statemachine.MachineState;
import util.statemachine.Move;
import util.statemachine.Role;
import util.statemachine.StateMachine;
import util.statemachine.exceptions.GoalDefinitionException;
import util.statemachine.exceptions.MoveDefinitionException;
import util.statemachine.exceptions.TransitionDefinitionException;
import util.statemachine.implementation.prover.query.ProverQueryBuilder;

public class BooleanPropNetStateMachine extends StateMachine {

	/** The underlying proposition network */
	private PropNet pnet;
	/**
	 * An index from GdlTerms to Base Propositions. The truth value of base
	 * propositions determines the state
	 */
	private Map<GdlTerm, Proposition> basePropositions;
	/**
	 * An index from GdlTerms to Input Propositions. Input propositions
	 * correspond to moves a player can take
	 */
	private Map<GdlTerm, Proposition> inputPropositions;
	/**
	 * The terminal proposition. If the terminal proposition's value is true,
	 * the game is over
	 */
	private Proposition terminal;
	/** Maps roles to their legal propositions */
	private Map<Role, Set<Proposition>> legalPropositions;
	/** Maps roles to their goal propositions */
	private Map<Role, Set<Proposition>> goalPropositions;
	/** The topological ordering of the propositions */
	private List<Proposition> ordering;
	/**
	 * Set to true and everything else false, then propagate the truth values to
	 * compute the initial state
	 */
	private Proposition init;
	/** The roles of different players in the game */
	private List<Role> roles;
	/**
	 * A map between legal and input propositions. The map contains mappings in
	 * both directions
	 */
	private Map<Proposition, Proposition> legalInputMap;
	
	private Operator operator;
	private Proposition[] booleanOrdering;
	private Map<Proposition, Integer> propMap;
	private int internalPropIndex;

	
	/**
	 * Initializes the PropNetStateMachine. You should compute the topological
	 * ordering here. Additionally you can compute the initial state here, if
	 * you want.
	 * 
	 * We should do this and then throw away the init node + it's connections
	 */
	@Override
	public void initialize(List<Gdl> description) {
		pnet = CachedPropNetFactory.create(description);
		roles = computeRoles(description);
		basePropositions = pnet.getBasePropositions();
		inputPropositions = pnet.getInputPropositions();
		terminal = pnet.getTerminalProposition();
		legalPropositions = pnet.getLegalPropositions();
		init = pnet.getInitProposition();
		goalPropositions = pnet.getGoalPropositions();
		legalInputMap = pnet.getLegalInputMap();		
		ordering = getOrdering();
		pnet.renderToFile("D:\\Code\\Stanford\\cs227b_svn\\logs\\test.out");
		
		internalPropIndex = 1 + basePropositions.size() + inputPropositions.size();
		initBooleanOrderingAndPropMap();
		initOperator();
	}
	
	

	/**
	 * Computes if the state is terminal. Should return the value of the
	 * terminal proposition for the state.
	 */
	@Override
	public boolean isTerminal(MachineState state) {
		initBasePropositionsFromState(state);
		propagate();
		if (terminal.getValue()) {
			Log.println('p', "Terminal detected in " + state);
		}
		return terminal.getValue();
	}

	/**
	 * Computes the goal for a role in the current state. Should return the
	 * value of the goal proposition that is true for role. If the number of
	 * goals that are true for role != 1, then you should throw a
	 * GoalDefinitionException
	 */
	@Override
	public int getGoal(MachineState state, Role role) throws GoalDefinitionException {
		initBasePropositionsFromState(state);
		Set<Proposition> goals = goalPropositions.get(role);

		boolean goalFound = false;
		int goalValue = -1;
		for (Proposition goal : goals) {
			if (goal.getValue()) {
				if (goalFound) {
					throw new GoalDefinitionException(state, role);
				} else {
					Log.println('p', "Goal found: " + goal.getName().toSentence().get(1));
					goalValue = getGoalValue(goal);
					goalFound = true;
				}
			}
		}

		return goalValue;
	}

	/**
	 * Returns the initial state. The initial state can be computed by only
	 * setting the truth value of the init proposition to true, and then
	 * computing the resulting state.
	 */
	@Override
	public MachineState getInitialState() {
		try {
			HashSet<GdlSentence> trueSentences = new HashSet<GdlSentence>();

			initBasePropositionsFromState(getMachineStateFromSentenceList(new HashSet<GdlSentence>()));

			init.setValue(true);
			propagate();
			init.setValue(false);
			for (GdlTerm propName : basePropositions.keySet()) {
				if (basePropositions.get(propName).getValue()) {
					trueSentences.add(propName.toSentence());
				}
			}
			for (GdlTerm propName : basePropositions.keySet()) {
				if (basePropositions.get(propName).getValue()) {
					trueSentences.add(propName.toSentence());
				}
			}
			MachineState newState = getMachineStateFromSentenceList(trueSentences);
			Log.println('p', "Init with " + newState);
			return newState;
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		return null;
	}

	/**
	 * Computes the legal moves for role in state
	 */
	@Override
	public List<Move> getLegalMoves(MachineState state, Role role) throws MoveDefinitionException {
		try {
			List<Move> legalMoves = new LinkedList<Move>();

			Set<Proposition> legals = legalPropositions.get(role);

			// Clear initial moves
			for (Proposition legal : legals) {
				legalInputMap.get(legal).setValue(false);
			}
			initBasePropositionsFromState(state);
			for (Proposition legal : legals) {
				Proposition input = legalInputMap.get(legal);
				input.setValue(true);
				propagateInternalOnly();
				if (legal.getValue()) {
					legalMoves.add(getMoveFromProposition(input));
				}
				input.setValue(false);
			}
			Log.println('p', "Legal moves in " + state + " for " + role + " = " + legalMoves);
			return legalMoves;
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		return null;
	}

	/**
	 * Computes the next state given state and the list of moves.
	 */
	@Override
	public MachineState getNextState(MachineState state, List<Move> moves)
			throws TransitionDefinitionException {
		try {
			
			// Set up the base propositions
			initBasePropositionsFromState(state);

			// Set up the input propositions
			List<GdlTerm> doeses = toDoes(moves);

			for (Proposition anyInput : inputPropositions.values()) {
				anyInput.setValue(false);
			}

			for (GdlTerm does : doeses) {
				Proposition trueInput = inputPropositions.get(does);
				trueInput.setValue(true);
			}

			propagate();
			HashSet<GdlSentence> trueSentences = new HashSet<GdlSentence>();
			for (Proposition prop : basePropositions.values()) {
				if (prop.getValue()) {
					trueSentences.add(prop.getName().toSentence());
				}
			}
			MachineState newState = getMachineStateFromSentenceList(trueSentences);
			Log.println('p', "From " + state + " to " + newState + " via " + moves);
			return newState;
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		return null;
	}

	private boolean[] initBasePropositionsFromState(MachineState state) {
		if (state instanceof BooleanMachineState) {
			boolean[] props = new boolean[booleanOrdering.length];
			boolean[] baseProps = ((BooleanMachineState)state).getBooleanContents();
			for (int i = 0; i < baseProps.length; i++) {
				props[i+1] = baseProps[i];
			}
			return props;
		} else {
			Set<GdlSentence> initialTrueSentences = state.getContents();
			for (GdlTerm propName : basePropositions.keySet()) {
				basePropositions.get(propName).setValue(
						initialTrueSentences.contains(propName.toSentence()));
			}
			return generatePropArray(1, basePropositions.size());
			
		}
	}
	
	
	private void propagateInternalOnly() {
		
		// All the input propositions are set, update all propositions in order
		for (Proposition prop : ordering) {
			prop.setValue(prop.getSingleInput().getValue());
		}
	}

	private void propagate() {
		boolean[] props = operator.propagate(generatePropArray());
		propagateInternalOnly();

		// All the internal propositions are updated, update all base
		// propositions
		for (Proposition baseProposition : basePropositions.values()) {
			baseProposition.setValue(baseProposition.getSingleInput().getValue());
		}
		
		for (int i = 0; i < booleanOrdering.length; i++) {
			if (booleanOrdering[i].getValue() != props[i]) {
				System.err.println("INCORRECT!");
			}
		}
	}

	/**
	 * This should compute the topological ordering of propositions. Each
	 * component is either a proposition, logical gate, or transition. Logical
	 * gates and transitions only have propositions as inputs. The base
	 * propositions and input propositions should always be exempt from this
	 * ordering The base propositions values are set from the MachineState that
	 * operations are performed on and the input propositions are set from the
	 * Moves that operations are performed on as well (if any).
	 * 
	 * @return The order in which the truth values of propositions need to be
	 *         set.
	 */
	public List<Proposition> getOrdering() {
		LinkedList<Proposition> order = new LinkedList<Proposition>();

		// All of the components in the PropNet
		HashSet<Component> components = new HashSet<Component>(pnet.getComponents());
		// All of the propositions in the prop net
		HashSet<Proposition> propositions = new HashSet<Proposition>(pnet.getPropositions());
		Log.println('p', "Components: " + components);

		// Remove all base propositions
		Collection<Proposition> basePropositionsValues = basePropositions.values();
		propositions.removeAll(basePropositionsValues);
		components.removeAll(basePropositionsValues);
		
		// Remove all propositions with no inputs (covers init, input, and spurious)
		Iterator<Proposition> iterator = propositions.iterator();
		while (iterator.hasNext()) {
			Proposition prop = iterator.next();
			if (prop.getInputs().size() == 0) {
				iterator.remove();
				components.remove(prop);
			}
		}

		// Use DFS to compute topological sort of propositions
		while (!propositions.isEmpty()) {
			Proposition currComponent = propositions.iterator().next();
			topologicalSort(currComponent, order, propositions, components);
		}
		Log.println('p', "Order: " + order);
		return order;
	}

	private void topologicalSort(Component currComponent, LinkedList<Proposition> order,
			HashSet<Proposition> propositions, HashSet<Component> components) {
		for (Component input : currComponent.getInputs()) {
			if (components.contains(input)) { // not yet visited
				topologicalSort(input, order, propositions, components);
			}
		}
		if (currComponent instanceof Proposition) {
			order.add((Proposition) currComponent);
			propositions.remove(currComponent);
		}
		components.remove(currComponent);
	}

	/** Already implemented for you */
	@Override
	public Move getMoveFromSentence(GdlSentence sentence) {
		return new PropNetMove(sentence);
	}

	/** Already implemented for you */
	@Override
	public MachineState getMachineStateFromSentenceList(Set<GdlSentence> sentenceList) {
		return new PropNetMachineState(sentenceList);
	}

	/** Already implemented for you */
	@Override
	public Role getRoleFromProp(GdlProposition proposition) {
		return new PropNetRole(proposition);
	}

	/** Already implemented for you */
	@Override
	public List<Role> getRoles() {
		return roles;
	}

	/* Helper methods */
	/**
	 * The Input propositions are indexed by (does ?player ?action) This
	 * translates a List of Moves (backed by a sentence that is simply ?action)
	 * to GdlTerms that can be used to get Propositions from inputPropositions
	 * and accordingly set their values etc. This is a naive implementation when
	 * coupled with setting input values, feel free to change this for a more
	 * efficient implementation.
	 * 
	 * @param moves
	 * @return
	 */
	private List<GdlTerm> toDoes(List<Move> moves) {
		List<GdlTerm> doeses = new ArrayList<GdlTerm>(moves.size());
		Map<Role, Integer> roleIndices = getRoleIndices();

		for (int i = 0; i < roles.size(); i++) {
			int index = roleIndices.get(roles.get(i));
			doeses.add(ProverQueryBuilder.toDoes(roles.get(i), moves.get(index)).toTerm());
		}
		return doeses;
	}

	/**
	 * Takes in a Legal Proposition and returns the appropriate corresponding
	 * Move
	 * 
	 * @param p
	 * @return a PropNetMove
	 */

	public static Move getMoveFromProposition(Proposition p) {
		return new PropNetMove(p.getName().toSentence().get(1).toSentence());
	}

	/**
	 * Helper method for parsing the value of a goal proposition
	 * 
	 * @param goalProposition
	 * @return the integer value of the goal proposition
	 */

	private int getGoalValue(Proposition goalProposition) {
		GdlRelation relation = (GdlRelation) goalProposition.getName().toSentence();
		GdlConstant constant = (GdlConstant) relation.get(1);
		return Integer.parseInt(constant.toString());
	}

	/**
	 * A Naive implementation that computes a PropNetMachineState from the true
	 * BasePropositions. This is correct but slower than more advanced
	 * implementations You need not use this method!
	 * 
	 * @return PropNetMachineState
	 */

	public PropNetMachineState getStateFromBase() {
		Set<GdlSentence> contents = new HashSet<GdlSentence>();
		for (Proposition p : basePropositions.values()) {
			p.setValue(p.getSingleInput().getValue());
			if (p.getValue()) {
				contents.add(p.getName().toSentence());
			}

		}
		return new PropNetMachineState(contents);
	}

	/**
	 * Helper method, used to get compute roles. You should only be using this
	 * for Role indexing (because of compatibility with the GameServer state
	 * machine's roles)
	 * 
	 * @param description
	 * @return the list of roles for the current game. Compatible with the
	 *         GameServer's state machine.
	 */
	private List<Role> computeRoles(List<Gdl> description) {
		List<Role> roles = new ArrayList<Role>();
		for (Gdl gdl : description) {
			if (gdl instanceof GdlRelation) {
				GdlRelation relation = (GdlRelation) gdl;
				if (relation.getName().getValue().equals("role")) {
					roles.add(new PropNetRole((GdlProposition) relation.get(0).toSentence()));
				}
			}
		}
		return roles;
	}
	
	private void initBooleanOrderingAndPropMap() {
		booleanOrdering = new Proposition[1 + basePropositions.size() + inputPropositions.size() + ordering.size()];
		propMap = new HashMap<Proposition, Integer>();
		
		booleanOrdering[0] = init;
		propMap.put(init, 0);
		int filledSoFar = 1;
		
		for (Proposition p : basePropositions.values()) {
			booleanOrdering[filledSoFar] = p;
			propMap.put(p, filledSoFar);
			filledSoFar++;
		}
		
		for (Proposition p : inputPropositions.values()) {
			booleanOrdering[filledSoFar] = p;
			propMap.put(p, filledSoFar);
			filledSoFar++;
		}
		
		for (Proposition p : ordering) {
			booleanOrdering[filledSoFar] = p;
			propMap.put(p, filledSoFar);
			filledSoFar++;
		}
	}



	private void initOperator() {
		String propagateBody = generatePropagateMethodBody();
		String internalOnlyBody = generatePropagateInternalOnlyMethodBody();
		try {
			CtClass operatorInterface = ClassPool.getDefault().get("util.statemachine.implementation.propnet.Operator");
			CtClass operatorClass = ClassPool.getDefault().makeClass("util.statemachine.implementation.propnet.OperatorClass");
			operatorClass.addInterface(operatorInterface);
			CtMethod internalPropagateMethod = CtNewMethod.make(internalOnlyBody, operatorClass);
			operatorClass.addMethod(internalPropagateMethod);
			CtMethod propagateMethod = CtNewMethod.make(propagateBody, operatorClass);
			operatorClass.addMethod(propagateMethod);
	
			operator = (Operator)operatorClass.toClass().newInstance();
			Log.println('c', "Sucessfully initialized operator!");
		} catch (IllegalAccessException ex) {
			ex.printStackTrace();
		} catch (InstantiationException ex) {
			ex.printStackTrace();
		} catch (CannotCompileException ex) {
			ex.printStackTrace();
		} catch (NotFoundException ex) {
			ex.printStackTrace();
		}
		
	}
	
	private String generatePropagateMethodBody() {
		StringBuilder body = new StringBuilder();
		body.append("public boolean[] propagate(boolean[] props) {\n");
		body.append("props = this.propagateInternalOnly(props);");
		
		for (int i = 1; i < basePropositions.size() + 1; i++) {
			Proposition propositionFromOrdering = booleanOrdering[i];
			Component comp = propositionFromOrdering.getSingleInput();
			if (comp instanceof Constant) {
				body.append("props[" + i + "] = " + comp.getValue() + ";\n");
			} else if (comp instanceof Transition) {
				if (!propMap.containsKey(comp.getSingleInput())) {
					body.append("props[" + i + "] =" + comp.getSingleInput().getValue() + ";\n");
				} else {
					body.append("props[" + i + "] = props[" + propMap.get(comp.getSingleInput()) + "];\n");
				}
			} else {
				throw new RuntimeException("Unexpected Class");
			}
		}
		
		body.append("return props;\n");
		body.append("}");
		Log.println('c', body.toString());
		return body.toString();
	}
	
	private String generatePropagateInternalOnlyMethodBody() {
		StringBuilder body = new StringBuilder();
		body.append("public boolean[] propagateInternalOnly(boolean[] props) {\n");
		
		for (int i = internalPropIndex; i < ordering.size() + internalPropIndex; i++) {
			Proposition propositionFromOrdering = ordering.get(i - internalPropIndex);
			Component comp = propositionFromOrdering.getSingleInput();
			if (comp instanceof Constant) {
				body.append("props[" + i + "] = " + comp.getValue() + ";\n");
			} else if (comp instanceof Not) {
				if (!propMap.containsKey(comp.getSingleInput())) {
					body.append("props[" + i + "] =!" + comp.getSingleInput().getValue() + ";\n");
				} else {
					body.append("props[" + i + "] = !props[" + propMap.get(comp.getSingleInput()) + "];\n");
				}
			} else if (comp instanceof And) {
				Set<Component> connected = comp.getInputs();
				StringBuilder and = new StringBuilder();
				and.append("props[" + i + "] = true");
				
				for (Component prop : connected) {
					if (!propMap.containsKey(prop)) { //if the proposition is not in the proposition map, it is never changed: it is effectively a constant
						if (prop.getValue()) {
							continue;
						} else {
							and = new StringBuilder("props[" + i + "] = false");
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
				or.append("props[" + i + "] = false");
				
				for (Component prop : connected) {
					if (!propMap.containsKey(prop)) { //if the proposition is not in the proposition map, it is never changed: it is effectively a constant
						if (prop.getValue()) {
							or = new StringBuilder("props[" + i + "] = true");
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
		
		body.append("return props;\n");
		body.append("}");
		Log.println('c', body.toString());
		return body.toString();
	}

	
	private boolean[] generatePropArray() {
		return generatePropArray(0 ,internalPropIndex);
	}

	private boolean[] generatePropArray(int start, int end) {
		boolean[] props = new boolean[booleanOrdering.length];
		for (int i = start; i < end; i++) {
			props[i] = booleanOrdering[i].getValue();
		}
		
		return props;
	}

	private String booleanArrayToString(boolean[] array) {
		String str = "[";
		for (int i = 0; i < array.length; i++) {
			str += array[i] ? "1" : "0";
		}
		str += "]";
		return str;
	}
}
