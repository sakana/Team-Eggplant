package util.statemachine.implementation.propnet;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import player.gamer.statemachine.eggplant.misc.Log;
import player.gamer.statemachine.eggplant.misc.StateMachineFactory;
import util.gdl.grammar.Gdl;
import util.gdl.grammar.GdlProposition;
import util.gdl.grammar.GdlRelation;
import util.gdl.grammar.GdlSentence;
import util.gdl.grammar.GdlTerm;
import util.propnet.architecture.BooleanPropNet;
import util.propnet.architecture.Component;
import util.propnet.architecture.components.And;
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
import util.statemachine.implementation.propnet.cache.CachedBooleanPropNetStateMachine;
import util.statemachine.implementation.prover.query.ProverQueryBuilder;

public class BooleanPropNetStateMachine extends StateMachine {

	private static final String PNET_FOLDER = "pnet";
	private static final String ORIGINAL_PNET_PATH = "pnet" + File.separator + "orig.dot";
	
	/** The original description */ 
	private List<Gdl> description;
	
	/** The underlying proposition network */
	private BooleanPropNet pnet;
	
	/** References to every Proposition in the PropNet. */
	private Proposition[] propIndex;
	
	/** References to every Proposition in the PropNet. */
	private Map<Proposition, Integer> propMap;
	
	/** References to every BaseProposition in the PropNet, indexed by name. */
	private Map<GdlTerm, Integer> basePropMap;
	
	/** References to every InputProposition in the PropNet, indexed by name. */
	private Map<GdlTerm, Integer> inputPropMap;
	
	/**
	 * References to every LegalProposition in the PropNet, indexed by player
	 * name.
	 */
	private int[][] legalPropMap;
	
	/**
	 * A map between legal and input propositions. The map contains mappings in
	 * both directions
	 */
	private int[] legalInputMap;
	
	/**
	 * References to every GoalProposition in the PropNet, indexed by player
	 * name.
	 */
	private int[][][] goalPropMap;
	
	/** A the index of the single, unique InitProposition. */
	private int initIndex;
	
	/** A the index of the first BaseProposition. */
	private int basePropStart;
	
	/** A the index of the first InputProposition. */
	private int inputPropStart;
	
	/** A the index of the first InternalProposition. */
	private int internalPropStart;
	
	/** A the index of the single, unique TerminalProposition. */
	private int terminalIndex;
	
	private int numProps;
	
	/** The topological ordering of the propositions */
	private List<Proposition> defaultOrdering;
	
	/** The roles of different players in the game */
	private List<Role> rolesList;
	
	private Role[] roleIndex;
	private Map<Role, Integer> roleMap;
	
	private Move[] moveIndex;
	
	/** Latch mechanism */
	private ArrayList<HashMap<Integer, int[]>> sameTurnEffects;
	private ArrayList<HashMap<Integer, int[]>> nextTurnEffects;
	private List<Integer> trueLatches;
	private List<Integer> falseLatches;
	private Set<Proposition> satisfiedLatches;
	private Set<Proposition> relevantPropositions;
	
	private Operator nativeOperator;
	private Operator javassistOperator;
	private Operator operator;
	private Object operatorLock;
	private Role mainRole;
	
	public BooleanPropNetStateMachine() {
		super();
		mainRole = null;
	}
	
	public BooleanPropNetStateMachine(GdlProposition roleGDL) {
		super();
		mainRole = getRoleFromProp(roleGDL);
	}

	public BooleanPropNetStateMachine(Role role) {
		super();
		mainRole = role;
	}
	
	public BooleanPropNetStateMachine(BooleanPropNetStateMachine copy) {
		this.basePropMap = new HashMap<GdlTerm, Integer>(copy.basePropMap);
		this.basePropStart = copy.basePropStart;
		this.defaultOrdering = new LinkedList<Proposition>(copy.defaultOrdering);
		this.description = copy.description;
		this.falseLatches = copy.falseLatches;
	}
	
	/**
	 * Initializes the PropNetStateMachine. You should compute the topological
	 * ordering here. Additionally you can compute the initial state here, if
	 * you want.
	 * 
	 * We should do this and then throw away the init node + it's connections
	 */
	@Override
	public void initialize(List<Gdl> description) {
		this.description = description;
		this.pnet = new BooleanPropNet(CachedPropNetFactory.create(description));
		this.rolesList = computeRoles(description);
		initializeFromPropNet(this.pnet, false);
		this.pnet.renderToFile(ORIGINAL_PNET_PATH);
	}
	
	public void initializeFactor(Set<Component> components, List<Role> roles) {
		this.description = null;
		this.pnet = new BooleanPropNet(components);
		this.rolesList = roles;
		initializeFromPropNet(this.pnet, true);
	}

	private void initializeFromPropNet(BooleanPropNet pnet, boolean isFactor) {
		propIndex = pnet.getPropIndex();
		numProps = propIndex.length;
		propMap = pnet.getPropMap();
		basePropMap = pnet.getBasePropMap();
		inputPropMap = pnet.getInputPropMap();
		legalInputMap = pnet.getLegalInputMap();
		initIndex = pnet.getInitIndex();
		basePropStart = pnet.getBasePropStart();
		inputPropStart = pnet.getInputPropStart();
		internalPropStart = pnet.getInternalPropStart();
		terminalIndex = pnet.getTerminalIndex();
		
		computeRoleIndices(rolesList);
		
		Map<Role, int[]> rolesLegalPropMap = pnet.getLegalPropMap();
		Map<Role, int[][]> rolesGoalPropMap = pnet.getGoalPropMap();
		legalPropMap = new int[roleIndex.length][];
		goalPropMap = new int[roleIndex.length][][];
		for (int role = 0; role < roleIndex.length; role++) {
			legalPropMap[role] = rolesLegalPropMap.get(roleIndex[role]);
			goalPropMap[role] = rolesGoalPropMap.get(roleIndex[role]);
		}
		
		moveIndex = new Move[numProps]; 
		for (int i = inputPropStart; i < internalPropStart; i++) {
			moveIndex[i] = getMoveFromProposition(propIndex[i]);
		}
		
		defaultOrdering = getOrdering(null);
		
		if (mainRole == null)
			mainRole = roleIndex[0];

		if (!isFactor) {
			calculatePropEffects();
			initOperator(isFactor);
		}
	}

	/**
	 * Computes if the state is terminal. Should return the value of the
	 * terminal proposition for the state.
	 */
	@Override
	public boolean isTerminal(MachineState state) {
		try {
			boolean[] props = initBasePropositionsFromState(state);
			operator.propagateTerminalOnly(props);
			return props[terminalIndex];
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		return false;
	}
	
	/** 
	 * Retrieves the goal values in ascending numerical order.
	 */
	public int[] getGoalValues(Role role) {
		int roleNum = roleMap.get(role);
		int[][] goals = goalPropMap[roleNum];
		int[] values = new int[goals.length];
		for (int i = 0; i < goals.length; i++) {
			values[i] = goals[i][1];
		}
		Arrays.sort(values);
		return values;
	}

	/**
	 * Computes the goal for a role in the current state. Should return the
	 * value of the goal proposition that is true for role. If the number of
	 * goals that are true for role != 1, then you should throw a
	 * GoalDefinitionException
	 */
	@Override
	public int getGoal(MachineState state, Role role) throws GoalDefinitionException {
		try {
			boolean[] props = initBasePropositionsFromState(state);
			int roleIndex = roleMap.get(role);
			int[][] goals = goalPropMap[roleIndex];
			operator.propagateGoalOnly(props, roleIndex);
			boolean goalFound = false;
			int goalValue = -1;
			for (int i = 0; i < goals.length; i++) {
				if (props[goals[i][0]]) {
					if (goalFound) {
						throw new GoalDefinitionException(state, role);
					} else {
						goalValue = goals[i][1];
						goalFound = true;
					}
				}
			}

			return goalValue;
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		return -1;

	}

	/**
	 * Returns the initial state. The initial state can be computed by only
	 * setting the truth value of the init proposition to true, and then
	 * computing the resulting state.
	 */
	@Override
	public BooleanMachineState getInitialState() {
		boolean[] props = new boolean[numProps];
		props[initIndex] = true;
		operator.propagate(props);
		return new BooleanMachineState(Arrays.copyOfRange(props, basePropStart, inputPropStart), propIndex);
	}

	/**
	 * Computes the legal moves for role in state
	 */
	@Override
	public List<Move> getLegalMoves(MachineState state, Role role) throws MoveDefinitionException {
		List<Move> legalMoves = new LinkedList<Move>();

		int roleIndex = roleMap.get(role);
		
		int[] legals = legalPropMap[roleIndex];			

		boolean[] props = initBasePropositionsFromState(state);
		
		// Clear initial moves
		for (int i = 0; i < legals.length; i++) {
			props[legals[i]] = false;
		}
		for (int i = 0; i < legals.length; i++) {
			int inputIndex = legalInputMap[legals[i]];
			props[inputIndex] = true;
			operator.propagateLegalOnly(props, roleIndex, i);
			if (props[legals[i]]) {
				legalMoves.add(moveIndex[inputIndex]);
			}
			props[inputIndex] = false;
		}
		return legalMoves;
	}

	/**
	 * Computes the next state given state and the list of moves.
	 */
	@Override
	public MachineState getNextState(MachineState state, List<Move> moves)
	throws TransitionDefinitionException {
		// Set up the base propositions
		boolean[] props = initBasePropositionsFromState(state);

		// Set up the input propositions
		List<GdlTerm> doeses = toDoes(moves);

		// All input props start as false

		for (GdlTerm does : doeses) {
			//DEBUG  Log.println('c', "Marking move with " + does);
			props[inputPropMap.get(does)] = true;
		}

		//DEBUG  Log.println('c', "Before propagate: " + Arrays.toString(props));
		operator.propagate(props);
		//DEBUG  Log.println('c', "After propagate: " + Arrays.toString(props));
		return new BooleanMachineState(Arrays.copyOfRange(props, basePropStart, inputPropStart), propIndex);
	}
	
	// Must be called only once per turn!
	public void updateSatisfiedLatches(MachineState state) {
		if (trueLatches.size() + falseLatches.size() == 0)
			return;
		try {
			// Set up the base propositions
			boolean[] props = initBasePropositionsFromState(state);
		
			operator.propagateInternal(props);

			Iterator<Integer> trueIterator = trueLatches.iterator();
			while (trueIterator.hasNext()) {
				Integer latch = trueIterator.next();
				if (props[latch]) {
					if (satisfiedLatches.add(propIndex[latch])) {
						trueIterator.remove();
						for (int otherProp : sameTurnEffects.get(latch).keySet()) {
							relevantPropositions.remove(propIndex[otherProp]);
						}
						for (int otherProp : nextTurnEffects.get(latch).keySet()) {
							relevantPropositions.remove(propIndex[otherProp]);
						}
					}
				}
			}
			Iterator<Integer> falseIterator = falseLatches.iterator();
			while (falseIterator.hasNext()) {
				Integer latch = falseIterator.next();
				if (!props[latch]) {
					if (satisfiedLatches.add(propIndex[latch])) {
						falseIterator.remove();
						for (int otherProp : sameTurnEffects.get(latch).keySet()) {
							relevantPropositions.remove(propIndex[otherProp]);
						}
						for (int otherProp : nextTurnEffects.get(latch).keySet()) {
							relevantPropositions.remove(propIndex[otherProp]);
						}
					}
				}
			}
			
			Log.println('l', satisfiedLatches.size() + " satified latches, " + relevantPropositions.size() + " relevant props");

		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}

	private boolean[] initBasePropositionsFromState(MachineState state) {
		if (state instanceof BooleanMachineState) {
			boolean[] props = new boolean[numProps];
			boolean[] baseProps = ((BooleanMachineState) state).getBooleanContents();
			System.arraycopy(baseProps, 0, props, basePropStart, baseProps.length);
			return props;
		} else {
			Set<GdlSentence> initialTrueSentences = state.getContents();
			boolean[] props = new boolean[numProps];
			for (GdlTerm propName : basePropMap.keySet()) {
				props[basePropMap.get(propName)] = initialTrueSentences.contains(propName.toSentence());
			}
			return props;
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
	public List<Proposition> getOrdering(int[] preferredOrdering) {
		LinkedList<Proposition> order = new LinkedList<Proposition>();

		// All of the components in the PropNet
		HashSet<Component> components = new HashSet<Component>(pnet.getComponents());
		// Log.println('p', "Components: " + components);
		
		// All of the internal propositions in the prop net
		HashSet<Proposition> propositions = new HashSet<Proposition>();
		for (int i = internalPropStart; i < numProps; i++) {
			propositions.add(propIndex[i]);
		}
		
		// Remove all base propositions from components 
		for (int i = initIndex; i < inputPropStart; i++) {
			components.remove(propIndex[i]);
		}
		
		// TODO: Optimize this. Not efficient at all
		Iterator<Component> componentIterator = components.iterator();
		while (componentIterator.hasNext()) {
			Component component = componentIterator.next();
			if ((component instanceof Proposition) && component.getInputs().size() < 1) {
				componentIterator.remove();
			}
		}
		
		// Use DFS to compute topological sort of propositions
		if (preferredOrdering == null) { // sort all
			while (!propositions.isEmpty()) {
				Proposition currComponent = propositions.iterator().next();
				BooleanPropNet.topologicalSort(currComponent, order, propositions, components);
			}
		}
		else {
			for (int i = 0; i < preferredOrdering.length; i++) {
				Proposition currComponent = propIndex[preferredOrdering[i]];
				if (propositions.contains(currComponent)) {
					BooleanPropNet.topologicalSort(currComponent, order, propositions, components);
				}
			}
		}
		
		//DEBUG  Log.println('p', "Order: " + order);
		return order;
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
		return rolesList;
	}
	
	public int[][][] getGoalPropMap() {
		return goalPropMap;
	}
	
	public Map<Integer, int[]> getLatchesOn(int propNum) {
		Map<Integer, int[]> affectingLatches = new HashMap<Integer, int[]>(); 
		for (int latch : trueLatches) {
			if (sameTurnEffects.get(latch).containsKey(propNum)) {
				affectingLatches.put(latch, sameTurnEffects.get(latch).get(propNum));
			}
			if (nextTurnEffects.get(latch).containsKey(propNum)) {
				affectingLatches.put(latch, nextTurnEffects.get(latch).get(propNum));
			}
		}
		return affectingLatches;
	}
	
	public int getBasePropStart() {
		return basePropStart;
	}
	
	public int getInputPropStart() {
		return inputPropStart;
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

		for (int i = 0; i < rolesList.size(); i++) {
			int index = roleIndices.get(rolesList.get(i));
			doeses.add(ProverQueryBuilder.toDoes(rolesList.get(i), moves.get(index)).toTerm());
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
	
	private void computeRoleIndices(List<Role> roles) {
		roleIndex = new Role[roles.size()];
		roleMap = new HashMap<Role, Integer>();
		for (int i = 0; i < roles.size(); i++) {
			roleIndex[i] = roles.get(i);
			roleMap.put(roleIndex[i], i);
		}
	}

	private void initOperator(boolean isFactor) {
		List<Proposition> transitionOrdering = new ArrayList<Proposition>();
		for (int i = basePropStart; i < inputPropStart; i++) {
			transitionOrdering.add(propIndex[i]);
		}
		
		List<Proposition> terminalOrdering = getOrdering(new int[]{terminalIndex});
		/*
		for (Proposition prop : terminalOrdering) {
			Log.print('r', propMap.get(prop) + ", ");
		}
		Log.println('r', "\n");
		*/
		Log.println('r', "Terminal ordering : " + terminalOrdering.size());
		
		List<List<List<Proposition>>> legalOrderings = new LinkedList<List<List<Proposition>>>();
		for (int role = 0; role < roleIndex.length; role++) {
			List<List<Proposition>> legalOrderingForRole = new LinkedList<List<Proposition>>();
			for (int i = 0; i < legalPropMap[role].length; i++) {
				int legalProp = legalPropMap[role][i];
				legalOrderingForRole.add(getOrdering(new int[]{legalProp}));
				Log.println('r', "Role " + role + " legal ordering for " + propIndex[legalProp] + " : " + legalOrderingForRole.get(i).size());
			}
			legalOrderings.add(legalOrderingForRole);
		}
		
		List<List<Proposition>> goalOrderings = new LinkedList<List<Proposition>>();
		for (int role = 0; role < roleIndex.length; role++) {
			int[][] goalPropsAndValues = goalPropMap[role];
			int[] goalProps = new int[goalPropsAndValues.length];
			for (int i = 0; i < goalProps.length; i++) {
				goalProps[i] = goalPropsAndValues[i][0];
			}
			goalOrderings.add(getOrdering(goalProps));
			Log.println('r', "Role " + role + " goal ordering : " + goalOrderings.get(role).size());
		}
		
		operatorLock = new Object();

		long javassistStart = System.currentTimeMillis();
		Log.println('y', "Javassist started!");
		javassistOperator = OperatorFactory.buildOperator(propMap, transitionOrdering, defaultOrdering, terminalOrdering, legalOrderings, goalOrderings,
				legalPropMap, legalInputMap, inputPropStart, inputPropMap.size(), terminalIndex);
		setOperator(true);
		Log.println('y', "Javassist done!");
		long javassistEnd = System.currentTimeMillis();
		
		if (!isFactor) {
			StateMachineFactory.pushMachine(StateMachineFactory.CACHED_BPNSM_JAVASSIST, this);
			if (javassistEnd - javassistStart < 1000 * 10 && this.pnet.getComponents().size() < 5000) { 
				Log.println('y', "Native started!");
				nativeOperator = NativeOperatorFactory.buildOperator(propMap, transitionOrdering, defaultOrdering, terminalOrdering, legalOrderings,
						goalOrderings, legalPropMap, legalInputMap, inputPropStart, inputPropMap.size(), terminalIndex, goalPropMap[roleMap.get(mainRole)]);
				if (nativeOperator != null) {
					setOperator(false);
					Log.println('y', "Native done!");
					StateMachineFactory.pushMachine(StateMachineFactory.CACHED_BPNSM_NATIVE, this);
				}
			}

			if (rolesList.size() == 1) { // Try to factor only on single-player games 
				Log.println('y', "Factoring started!");
				factor();
				Log.println('y', "Factoring done!");
			}
			
		}
	}
	
	public void setOperator(boolean toJavassist) {
		synchronized(operatorLock) {
			if (toJavassist) {
				if (javassistOperator != null)
					operator = javassistOperator;
			}
			else {
				if (nativeOperator != null)
					operator = nativeOperator;
			}
		}
	}
	
	// The heuristic does not have access to most of the prop net info, so we pass in
	// arrays of length 1 in the first dimension and use it as a pointer / reference
	public void populateGoalHeuristicArrays(int role, float[][][][] significanceRef, int[][] goalValuesRef) {
		int numGoals = goalPropMap[role].length;
		goalValuesRef[0] = new int[numGoals];
		significanceRef[0] = new float[numGoals][][];
		for (int goal = 0; goal < numGoals; goal++) {
			// Log.println('x', "Sigs for role " + role + " and goal " + propIndex[goalPropMap[role][goal][0]]);
			significanceRef[0][goal] = calculateGoalHeuristic(goalPropMap[role][goal][0]);
			goalValuesRef[0][goal] = goalPropMap[role][goal][1];
			/*
				for (int i = basePropStart; i < inputPropStart; i++) {
					Log.println('x', propIndex[i] + " = " + Arrays.toString(significanceRef[0][goal][i-basePropStart]));
				}
			*/
		}
	}
	
	private float[][] calculateGoalHeuristic(int goalNum) {
		float[][] significance = new float[numProps][2];
		significance[goalNum][0] = 1;
		PriorityQueue<Integer> queue = new PriorityQueue<Integer>();
		queue.add(-goalNum); // reverse of natural ordering desired
		float trueSignificance, falseSignificance;
		int maxPropNum = numProps;
		while (!queue.isEmpty()) {
			int propNum = -queue.poll();
			if (propNum == maxPropNum) {
				continue;
			}
			
			maxPropNum = propNum;
			Proposition prop = propIndex[propNum];
			if (prop.getInputs().size() != 1) {
				continue;
			}
			Component connector = prop.getSingleInput();
			//int initialInputSize = connector.getInputs().size();
			Set<Component> inputs = new HashSet<Component>(connector.getInputs());
			inputs.retainAll(relevantPropositions);
			//inputs.removeAll(satisfiedLatches);
			//Log.println('x', "Processing " + prop + " " + significance[propNum][0] + "," + significance[propNum][1] + " with connector " + connector + " to " + inputs);
			if (connector instanceof And) {
				trueSignificance = significance[propNum][0] / inputs.size();
				falseSignificance = significance[propNum][1]; // * initialInputSize / inputs.size();
			}
			else if (connector instanceof Or) {
				trueSignificance = significance[propNum][0]; // * initialInputSize / inputs.size();
				falseSignificance = significance[propNum][1] / inputs.size();
			}
			else if (connector instanceof Not) {
				trueSignificance = significance[propNum][1];
				falseSignificance = significance[propNum][0];
			}
			else {
				continue;
			}
			for(Component input : inputs) {
				Integer prevPropNum = propMap.get(input);
				if (prevPropNum != null) {
					significance[prevPropNum][0] += trueSignificance;
					significance[prevPropNum][1] += falseSignificance;
					queue.add(-prevPropNum);
				}
			}
		}
		return Arrays.copyOfRange(significance, basePropStart, inputPropStart);
	}
	
	private void calculatePropEffects() {
		long start = System.currentTimeMillis();
		Log.println('l', "Begin latch calculations");
		//sameTurnEffects = new int[numProps][numProps][2];
		//nextTurnEffects = new int[numProps][numProps][2];
		sameTurnEffects = new ArrayList<HashMap<Integer, int[]>>();
		nextTurnEffects = new ArrayList<HashMap<Integer, int[]>>();
		for (int propNum = 0; propNum < numProps; propNum++) {
			sameTurnEffects.add(new HashMap<Integer, int[]>());
			nextTurnEffects.add(new HashMap<Integer, int[]>());
		}
		
		Set<Integer> goalNums = new HashSet<Integer>();
		for (int role = 0; role < goalPropMap.length; role++) {
			for (int goal = 0; goal < goalPropMap[role].length; goal++) {
				goalNums.add(goalPropMap[role][goal][0]);
			}
		}
		
		int[] sameTurnAr, sameTurnAr2, nextTurnAr;
		for (int propNum = numProps - 1; propNum >= 0; propNum--) {
			if (propNum >= inputPropStart && propNum < internalPropStart) {
				for (int nextPropNum = inputPropStart; nextPropNum < internalPropStart; nextPropNum++) {
					//sameTurnEffects[propNum][nextPropNum][1] = -1;
					sameTurnAr = sameTurnEffects.get(propNum).get(nextPropNum);
					if (sameTurnAr == null) {
						sameTurnAr = new int[2];
						sameTurnEffects.get(propNum).put(nextPropNum, sameTurnAr);
					}
					sameTurnAr[1] = -1;
				}
			}
			if (goalNums.contains(propNum)) {
	loop:		for (int role = 0; role < goalPropMap.length; role++) {
					for (int goal = 0; goal < goalPropMap[role].length; goal++) {
						if (propNum == goalPropMap[role][goal][0]) {
							for (int otherGoal = 0; otherGoal < goalPropMap[role].length; otherGoal++) {
								if (otherGoal == goal) {
									continue;
								}
								sameTurnAr = sameTurnEffects.get(propNum).get(goalPropMap[role][otherGoal][0]);
								if (sameTurnAr == null) {
									sameTurnAr = new int[2];
									sameTurnEffects.get(propNum).put(goalPropMap[role][otherGoal][0], sameTurnAr);
								}
								sameTurnAr[1] = -1;
							}
							break loop;
						}
					}
				}
			}
			//sameTurnEffects[propNum][propNum][0] = -1;
			//sameTurnEffects[propNum][propNum][1] = 1;
			sameTurnAr = sameTurnEffects.get(propNum).get(propNum);
			if (sameTurnAr == null) {
				sameTurnAr = new int[2];
				sameTurnEffects.get(propNum).put(propNum, sameTurnAr);
			}
			sameTurnAr[0] = -1;
			sameTurnAr[1] = 1;
			Proposition prop = propIndex[propNum];
			for (Component output : prop.getOutputs()) {
				{
					Proposition nextProp = (Proposition) output.getSingleOutput();
					if (!propMap.containsKey(nextProp))
						continue;
					//int nextPropNum = propMap.get(nextProp);	
					//DEBUG  Log.println('b', "Reached " + output + " " + nextProp + " " + nextPropNum);
				}
				if ((output instanceof And || output instanceof Or) && output.getInputs().size() == 1) {
					Proposition nextProp = (Proposition) output.getSingleOutput();
					int nextPropNum = propMap.get(nextProp);
					//sameTurnEffects[propNum][nextPropNum][0] = -1;
					//sameTurnEffects[propNum][nextPropNum][1] = 1;
					
					
					sameTurnAr = sameTurnEffects.get(propNum).get(nextPropNum);
					if (sameTurnAr == null) {
						sameTurnAr = new int[2];
						sameTurnEffects.get(propNum).put(nextPropNum, sameTurnAr);
					}
					sameTurnAr[0] = -1;
					sameTurnAr[1] = 1;
					HashMap<Integer, int[]> map = sameTurnEffects.get(nextPropNum); 
					for (Integer i : map.keySet()) {
						sameTurnAr2 = map.get(i);
						for (int tf = 0; tf < 2; tf++) {
							if (sameTurnAr2[tf] != 0) {
								//sameTurnEffects[propNum][i][tf] = sameTurnAr2[tf];
								sameTurnAr = sameTurnEffects.get(propNum).get(i);
								if (sameTurnAr == null) {
									sameTurnAr = new int[2];
									sameTurnEffects.get(propNum).put(i, sameTurnAr);
								}
								sameTurnAr[tf] = sameTurnAr2[tf];
							}
						}
					}
					map = nextTurnEffects.get(nextPropNum);
					for (Integer i : map.keySet()) {
						nextTurnAr = map.get(i);
						for (int tf = 0; tf < 2; tf++) {
							if (nextTurnAr[tf] != 0) {
								//nextTurnEffects[propNum][i][tf] = nextTurnAr[tf];
								sameTurnAr = nextTurnEffects.get(propNum).get(i);
								if (sameTurnAr == null) {
									sameTurnAr = new int[2];
									nextTurnEffects.get(propNum).put(i, sameTurnAr);
								}
								sameTurnAr[tf] = nextTurnAr[tf];
							}
						}
					} 
				}
				else if (output instanceof And) {
					Proposition nextProp = (Proposition) output.getSingleOutput();
					int nextPropNum = propMap.get(nextProp);
					//sameTurnEffects[propNum][nextPropNum][0] = -1;
					
					sameTurnAr = sameTurnEffects.get(propNum).get(nextPropNum);
					if (sameTurnAr == null) {
						sameTurnAr = new int[2];
						sameTurnEffects.get(propNum).put(nextPropNum, sameTurnAr);
					}
					sameTurnAr[0] = -1;
					HashMap<Integer, int[]> map = sameTurnEffects.get(nextPropNum); 
					for (Integer i : map.keySet()) {
						sameTurnAr2 = map.get(i);
						if (sameTurnAr2[0] != 0) {
							//sameTurnEffects[propNum][i][0] = sameTurnAr2[0];
							sameTurnAr = sameTurnEffects.get(propNum).get(i);
							if (sameTurnAr == null) {
								sameTurnAr = new int[2];
								sameTurnEffects.get(propNum).put(i, sameTurnAr);
							}
							sameTurnAr[0] = sameTurnAr2[0];
						}
					}
					map = nextTurnEffects.get(nextPropNum); 
					for (Integer i : map.keySet()) {
						nextTurnAr = map.get(i);
						if (nextTurnAr[0] != 0) {
							//nextTurnEffects[propNum][i][0] = nextTurnAr[0];
							sameTurnAr = nextTurnEffects.get(propNum).get(i);
							if (sameTurnAr == null) {
								sameTurnAr = new int[2];
								nextTurnEffects.get(propNum).put(i, sameTurnAr);
							}
							sameTurnAr[0] = nextTurnAr[0];
						}
					}
				}
				else if (output instanceof Or) {
					Proposition nextProp = (Proposition) output.getSingleOutput();
					int nextPropNum = propMap.get(nextProp);
					//sameTurnEffects[propNum][nextPropNum][1] = 1;
					
					
					sameTurnAr = sameTurnEffects.get(propNum).get(nextPropNum);
					if (sameTurnAr == null) {
						sameTurnAr = new int[2];
						sameTurnEffects.get(propNum).put(nextPropNum, sameTurnAr);
					}
					sameTurnAr[1] = 1;
					HashMap<Integer, int[]> map = sameTurnEffects.get(nextPropNum); 
					for (Integer i : map.keySet()) {
						sameTurnAr2 = map.get(i);
						if (sameTurnAr2[1] != 0) {
							//sameTurnEffects[propNum][i][1] = sameTurnAr2[1];
							sameTurnAr = sameTurnEffects.get(propNum).get(i);
							if (sameTurnAr == null) {
								sameTurnAr = new int[2];
								sameTurnEffects.get(propNum).put(i, sameTurnAr);
							}
							sameTurnAr[1] = sameTurnAr2[1];
						}
					}

					map = nextTurnEffects.get(nextPropNum); 
					for (Integer i : map.keySet()) {
						nextTurnAr = map.get(i);
						if (nextTurnAr[1] != 0) {
							//nextTurnEffects[propNum][i][1] = nextTurnAr[1];
							sameTurnAr = nextTurnEffects.get(propNum).get(i);
							if (sameTurnAr == null) {
								sameTurnAr = new int[2];
								nextTurnEffects.get(propNum).put(i, sameTurnAr);
							}
							sameTurnAr[1] = nextTurnAr[1];
						}
					}
				}
				else if (output instanceof Not) {
					Proposition nextProp = (Proposition) output.getSingleOutput();
					int nextPropNum = propMap.get(nextProp);
					//sameTurnEffects[propNum][nextPropNum][0] = 1;
					//sameTurnEffects[propNum][nextPropNum][1] = -1;
					
					sameTurnAr = sameTurnEffects.get(propNum).get(nextPropNum);
					if (sameTurnAr == null) {
						sameTurnAr = new int[2];
						sameTurnEffects.get(propNum).put(nextPropNum, sameTurnAr);
					}
					sameTurnAr[0] = 1;
					sameTurnAr[1] = -1;
					HashMap<Integer, int[]> map = sameTurnEffects.get(nextPropNum); 
					for (Integer i : map.keySet()) {
						sameTurnAr2 = map.get(i);
						for (int tf = 0; tf < 2; tf++) {
							if (sameTurnAr2[tf] != 0) {
								//sameTurnEffects[propNum][i][1-tf] = sameTurnAr2[tf];
								sameTurnAr = sameTurnEffects.get(propNum).get(i);
								if (sameTurnAr == null) {
									sameTurnAr = new int[2];
									sameTurnEffects.get(propNum).put(i, sameTurnAr);
								}
								sameTurnAr[1-tf] = sameTurnAr2[tf];
							}
						}
					}

					map = nextTurnEffects.get(nextPropNum); 
					for (Integer i : map.keySet()) {
						nextTurnAr = map.get(i);
						for (int tf = 0; tf < 2; tf++) {
							if (nextTurnAr[tf] != 0) {
								//nextTurnEffects[propNum][i][1-tf] = nextTurnAr[tf];
								sameTurnAr = nextTurnEffects.get(propNum).get(i);
								if (sameTurnAr == null) {
									sameTurnAr = new int[2];
									nextTurnEffects.get(propNum).put(i, sameTurnAr);
								}
								sameTurnAr[1-tf] = nextTurnAr[tf];
							}
						}
					}
				}
				else if (output instanceof Transition) {
					Proposition nextProp = (Proposition) output.getSingleOutput();
					int nextPropNum = propMap.get(nextProp);
					//nextTurnEffects[propNum][nextPropNum][0] = -1;
					//nextTurnEffects[propNum][nextPropNum][1] = 1;
					
					nextTurnAr = nextTurnEffects.get(propNum).get(nextPropNum);
					if (nextTurnAr == null) {
						nextTurnAr = new int[2];
						nextTurnEffects.get(propNum).put(nextPropNum, nextTurnAr);
					}
					nextTurnAr[0] = -1;
					nextTurnAr[1] = 1;
					//DEBUG  Log.println('v', "From " + propNum + " to " + nextPropNum);
				}
			}			
		}
		
		// Compute nextTurnEffects
		for (int index = 0; index < numProps; index++) {
			List<Integer> queue = new LinkedList<Integer>();
			boolean[] visited = new boolean[numProps];
			queue.addAll(nextTurnEffects.get(index).keySet());
			
			while (!queue.isEmpty()) {
				
				int propNum = queue.remove(0);
				visited[propNum] = true;
				//DEBUG  Log.println('v', "At index " + index + " " + queue + " " + propIndex[index] + " " + propIndex[index].getOutputs() + " processing " + propIndex[propNum]);
			
				for (int tf = 0; tf < 2; tf++) {
					if (nextTurnEffects.get(index).get(propNum)[tf] != 0) {
						int effectOnPropNum = (nextTurnEffects.get(index).get(propNum)[tf] == 1) ? 1 : 0;
						HashMap<Integer, int[]> map = sameTurnEffects.get(propNum);
						for (Integer nextPropNum : map.keySet()) {
							int value = map.get(nextPropNum)[effectOnPropNum];
							if (value != 0) {
								//nextTurnEffects[index][nextPropNum][tf] = value;
								nextTurnAr = nextTurnEffects.get(index).get(nextPropNum);
								if (nextTurnAr == null) {
									nextTurnAr = new int[2];
									nextTurnEffects.get(index).put(nextPropNum, nextTurnAr);
								}
								nextTurnAr[tf] = value;

								if (!visited[nextPropNum]) {
									queue.add(nextPropNum);
									visited[nextPropNum] = true;
								}
								
							}
						}
					}
				}
			}
		}
		
		
		trueLatches = new ArrayList<Integer>();
		falseLatches = new ArrayList<Integer>();
		satisfiedLatches = new HashSet<Proposition>();
		relevantPropositions = new HashSet<Proposition>(this.propMap.keySet());
		for (int index = 0; index < numProps; index++) {
			HashMap<Integer, int[]> nextMap = nextTurnEffects.get(index);
			if (nextMap.containsKey(index)) {
				int[] ar = nextMap.get(index);
				if (ar[0] == -1) {
					falseLatches.add(index);
				}
				if (ar[1] == 1) {
					trueLatches.add(index);
				}
			}
		}
		long end = System.currentTimeMillis();
		Log.println('l', trueLatches.size() + " true latches and " + falseLatches.size() + " false latches found in " + (end - start) + " ms");
		
	}
/*
	private void testPropEffects() {
		long start1 = System.currentTimeMillis();
		calculatePropEffects3();
		System.gc();
		long start2 = System.currentTimeMillis();
		Log.println('l', "Generated in " + (start2 - start1) + " ms");
		
		calculatePropEffects2();
		int[][][] same2 = sameTurnEffectsAr;
		int[][][] next2 = nextTurnEffectsAr;
		same2 = null;
		next2 = null;
		System.gc();
		long start3 = System.currentTimeMillis();
		System.err.println(start3 - start2);
		calculatePropEffects();
		int[][][] same3 = sameTurnEffectsAr;
		int[][][] next3 = nextTurnEffectsAr;
		same3 = null;
		next3 = null;
		System.gc();
		long end = System.currentTimeMillis();
		System.err.println(end - start3);
		
		
		for (int i = 0; i < same1.length; i++) {
			for (int j = 0; j < same1[i].length; j++) {
				for (int k = 0; k < same1[i][j].length; k++) {
					if (j < i && j >= internalPropStart && (same1[i][j][k] != 0 || same2[i][j][k] != 0)) {
						System.err.printf("Should not occur same[%d:%s][%d:%s][%d]: %d, %d\n", i, propIndex[i].toString(), j, propIndex[j].toString(), k, same1[i][j][k], same2[i][j][k]);
					}
					if (same1[i][j][k] != same2[i][j][k]) {
						System.err.printf("Discrepancy same[%d:%s][%d:%s][%d]: %d, %d\n", i, propIndex[i].toString(), j, propIndex[j].toString(), k, same1[i][j][k], same2[i][j][k]);
					}
					if (same1[i][j][k] != same3[i][j][k]) {
						System.err.printf("Discrepancy same[%d:%s][%d:%s][%d]: %d, %d\n", i, propIndex[i].toString(), j, propIndex[j].toString(), k, same1[i][j][k], same3[i][j][k]);
					}
					if (next1[i][j][k] != next2[i][j][k]) {
						System.err.printf("Discrepancy next[%d:%s][%d:%s][%d]: %d, %d\n", i, propIndex[i].toString(), j, propIndex[j].toString(), k, next1[i][j][k], next2[i][j][k]);
					}
					if (next1[i][j][k] != next3[i][j][k]) {
						System.err.printf("Discrepancy next[%d:%s][%d:%s][%d]: %d, %d\n", i, propIndex[i].toString(), j, propIndex[j].toString(), k, next1[i][j][k], next3[i][j][k]);
					}
				}
			}
		}
		
	}
	
	
	private void calculatePropEffects2() {
		Log.println('l', "Begin latch calculations 2");
		sameTurnEffectsAr = new int[numProps][numProps][2];
		nextTurnEffectsAr = new int[numProps][numProps][2];
		for (int propNum = numProps - 1; propNum >= 0; propNum--) {
			if (propNum >= inputPropStart && propNum < internalPropStart) {
				for (int nextPropNum = inputPropStart; nextPropNum < internalPropStart; nextPropNum++) {
					sameTurnEffectsAr[propNum][nextPropNum][1] = -1;
					
				}
			}
			sameTurnEffectsAr[propNum][propNum][0] = -1;
			sameTurnEffectsAr[propNum][propNum][1] = 1;
			Proposition prop = propIndex[propNum];
			for (Component output : prop.getOutputs()) {
				{
					Proposition nextProp = (Proposition) output.getSingleOutput();
					int nextPropNum = propMap.get(nextProp);
					Log.println('b', "Reached " + output + " " + nextProp + " " + nextPropNum);
				}
				if ((output instanceof And || output instanceof Or) && output.getInputs().size() == 1) {
					Proposition nextProp = (Proposition) output.getSingleOutput();
					int nextPropNum = propMap.get(nextProp);
					sameTurnEffectsAr[propNum][nextPropNum][0] = -1;
					sameTurnEffectsAr[propNum][nextPropNum][1] = 1;
					
					for (int i = nextPropNum; i < numProps; i++) {
						for (int tf = 0; tf < 2; tf++) {
							if (sameTurnEffectsAr[nextPropNum][i][tf] != 0) {
								sameTurnEffectsAr[propNum][i][tf] = sameTurnEffectsAr[nextPropNum][i][tf];
							}
							if (nextTurnEffectsAr[nextPropNum][i][tf] != 0) {
								nextTurnEffectsAr[propNum][i][tf] = nextTurnEffectsAr[nextPropNum][i][tf];
							}
						}
					}
					for (int i = 0; i < nextPropNum; i++) {
						for (int tf = 0; tf < 2; tf++) {
							if (nextTurnEffectsAr[nextPropNum][i][tf] != 0) {
								nextTurnEffectsAr[propNum][i][tf] = nextTurnEffectsAr[nextPropNum][i][tf];
							}
						}
					}
					
				}
				else if (output instanceof And) {
					Proposition nextProp = (Proposition) output.getSingleOutput();
					int nextPropNum = propMap.get(nextProp);
					sameTurnEffectsAr[propNum][nextPropNum][0] = -1;
					
					for (int i = nextPropNum; i < numProps; i++) {
						if (sameTurnEffectsAr[nextPropNum][i][0] != 0) {
							sameTurnEffectsAr[propNum][i][0] = sameTurnEffectsAr[nextPropNum][i][0];
						}
						if (nextTurnEffectsAr[nextPropNum][i][0] != 0) {
							nextTurnEffectsAr[propNum][i][0] = nextTurnEffectsAr[nextPropNum][i][0];
						}
					}
					for (int i = 0; i < nextPropNum; i++) {
						if (nextTurnEffectsAr[nextPropNum][i][0] != 0) {
							nextTurnEffectsAr[propNum][i][0] = nextTurnEffectsAr[nextPropNum][i][0];
						}
					}
				}
				else if (output instanceof Or) {
					Proposition nextProp = (Proposition) output.getSingleOutput();
					int nextPropNum = propMap.get(nextProp);
					sameTurnEffectsAr[propNum][nextPropNum][1] = 1;
					
					for (int i = nextPropNum; i < numProps; i++) {
						if (sameTurnEffectsAr[nextPropNum][i][1] != 0) {
							sameTurnEffectsAr[propNum][i][1] = sameTurnEffectsAr[nextPropNum][i][1];
						}
						if (nextTurnEffectsAr[nextPropNum][i][1] != 0) {
							nextTurnEffectsAr[propNum][i][1] = nextTurnEffectsAr[nextPropNum][i][1];
						}
					}
					for (int i = 0; i < nextPropNum; i++) {
						if (nextTurnEffectsAr[nextPropNum][i][1] != 0) {
							nextTurnEffectsAr[propNum][i][1] = nextTurnEffectsAr[nextPropNum][i][1];
						}
					}
				}
				else if (output instanceof Not) {
					Proposition nextProp = (Proposition) output.getSingleOutput();
					int nextPropNum = propMap.get(nextProp);
					sameTurnEffectsAr[propNum][nextPropNum][0] = 1;
					sameTurnEffectsAr[propNum][nextPropNum][1] = -1;
					
					for (int i = nextPropNum; i < numProps; i++) {
						for (int tf = 0; tf < 2; tf++) {
							if (sameTurnEffectsAr[nextPropNum][i][tf] != 0) {
								sameTurnEffectsAr[propNum][i][1-tf] = sameTurnEffectsAr[nextPropNum][i][tf];
							}
							if (nextTurnEffectsAr[nextPropNum][i][tf] != 0) {
								nextTurnEffectsAr[propNum][i][1-tf] = nextTurnEffectsAr[nextPropNum][i][tf];
							}
						}
					}
					for (int i = 0; i < nextPropNum; i++) {
						for (int tf = 0; tf < 2; tf++) {
							if (nextTurnEffectsAr[nextPropNum][i][tf] != 0) {
								nextTurnEffectsAr[propNum][i][1-tf] = nextTurnEffectsAr[nextPropNum][i][tf];
							}
						}
					}
					
				}
				else if (output instanceof Transition) {
					Proposition nextProp = (Proposition) output.getSingleOutput();
					int nextPropNum = propMap.get(nextProp);
					nextTurnEffectsAr[propNum][nextPropNum][0] = -1;
					nextTurnEffectsAr[propNum][nextPropNum][1] = 1;
					Log.println('v', "From " + propNum + " to " + nextPropNum);
				}
			}			
		}
		
		// Compute nextTurnEffects on sameTurnEffects on next turn props
	
		
		
		// Compute nextTurnEffects
		for (int index = 0; index < numProps; index++) {
			List<Integer> queue = new LinkedList<Integer>();
			boolean[] visited = new boolean[numProps];
			for (int propNum = 0; propNum < numProps; propNum++) {
				if (nextTurnEffectsAr[index][propNum][0] != 0 || nextTurnEffectsAr[index][propNum][1] != 0) {
					queue.add(propNum);
				}
			}
			
			while (!queue.isEmpty()) {
				
				int propNum = queue.remove(0);
				if (visited[propNum]) {
					continue;
				}
				Log.println('v', "At index " + index + " " + queue + " " + propIndex[index] + " " + propIndex[index].getOutputs() + " processing " + propIndex[propNum]);
				visited[propNum] = true;
			
				for (int tf = 0; tf < 2; tf++) {
					if (nextTurnEffectsAr[index][propNum][tf] != 0) {
						int effectOnPropNum = (nextTurnEffectsAr[index][propNum][tf] == 1) ? 1 : 0; 
						for (int nextPropNum = 0; nextPropNum < numProps; nextPropNum++) {
							if(sameTurnEffectsAr[propNum][nextPropNum][effectOnPropNum] != 0) {
								nextTurnEffectsAr[index][nextPropNum][tf] = sameTurnEffectsAr[propNum][nextPropNum][effectOnPropNum];
								queue.add(nextPropNum);
							}
						}
					}
				}
			}
		}
		
		trueLatches = new ArrayList<Integer>();
		falseLatches = new ArrayList<Integer>();
		satisfiedLatches = new HashSet<Proposition>();
		relevantPropositions = new HashSet<Proposition>(this.propMap.keySet());
		
		for (int index = 0; index < numProps; index++) {
			int countSameTurn = 0;
			int countNextTurn = 0;
			StringBuilder outputSame = new StringBuilder();
			StringBuilder outputNext = new StringBuilder();
			for (int propNum = 0; propNum < numProps; propNum++) {
				if (sameTurnEffectsAr[index][propNum][0] != 0) {
					countSameTurn++;
					outputSame.append("F[" + propNum + "]=" + ( sameTurnEffectsAr[index][propNum][0] == 1 ? "T" : "F" ) + ";");
				}
				if (sameTurnEffectsAr[index][propNum][1] != 0) {
					countSameTurn++;
					outputSame.append("T[" + propNum + "]=" + ( sameTurnEffectsAr[index][propNum][1] == 1 ? "T" : "F" ) + ";");
				}
				if (propNum != index && sameTurnEffectsAr[index][propNum][0] != 0 && sameTurnEffectsAr[index][propNum][1] != 0) {
					outputSame.append("=[" + propNum + "]!;");
				}
				if (nextTurnEffectsAr[index][propNum][0] != 0) {
					countNextTurn++;
					if (propNum == index && nextTurnEffectsAr[index][propNum][0] == -1) {
						falseLatches.add(propNum);
					}
					outputNext.append("F[" + propNum + "]=" + ( nextTurnEffectsAr[index][propNum][0] == 1 ? "T" : "F" ) + ";");
				}
				if (nextTurnEffectsAr[index][propNum][1] != 0) {
					countNextTurn++;
					if (propNum == index && nextTurnEffectsAr[index][propNum][1] == 1) {
						trueLatches.add(propNum);
					}
					outputNext.append("T[" + propNum + "]=" + ( nextTurnEffectsAr[index][propNum][1] == 1 ? "T" : "F" ) + ";");
				}
			}
			Log.println('b', "Count for index " + index + " = " + countSameTurn + " same turn / " + countNextTurn + " next turn ; (" + propIndex[index].getName() + "): " + outputSame.toString() + "//" + outputNext.toString());
		}
		Log.println('l', trueLatches.size() + " true latches and " + falseLatches.size() + " false latches found");
		
	}

	private void calculatePropEffects() {
		Log.println('l', "Begin latch calculations");
		sameTurnEffectsAr = new int[numProps][numProps][2];
		nextTurnEffectsAr = new int[numProps][numProps][2];
		
		for (int index = 0; index < numProps; index++) {
			List<Integer> queue = new LinkedList<Integer>();
			boolean[] visited = new boolean[numProps];
			queue.add(index);
			if (index >= inputPropStart && index < internalPropStart) {
				for (int propNum = inputPropStart; propNum < internalPropStart; propNum++) {
					sameTurnEffectsAr[index][propNum][1] = -1;
				}
			}
			sameTurnEffectsAr[index][index][0] = -1;
			sameTurnEffectsAr[index][index][1] = 1;
			while (!queue.isEmpty()) {
				int propNum = queue.remove(0);
				Proposition prop = propIndex[propNum];
				if (visited[propNum]) {
					continue;
				}
				visited[propNum] = true;
				Log.println('b', "Processing " + propNum + " " + prop + " " + prop.getOutputs());
				for (Component output : prop.getOutputs()) {
					{
						Proposition nextProp = (Proposition) output.getSingleOutput();
						int nextPropNum = propMap.get(nextProp);
						Log.println('b', "Reached " + output + " " + nextProp + " " + nextPropNum);
					}
					if ((output instanceof And || output instanceof Or) && output.getInputs().size() == 1 && (sameTurnEffectsAr[index][propNum][0] != 0 || sameTurnEffectsAr[index][propNum][1] != 0)) { // some single And / Or cannot be filtered due to special nodes
						Proposition nextProp = (Proposition) output.getSingleOutput();
						int nextPropNum = propMap.get(nextProp);
						for (int tf = 0; tf < 2; tf++) {
							if (sameTurnEffectsAr[index][propNum][tf] != 0) {
								sameTurnEffectsAr[index][nextPropNum][tf] = sameTurnEffectsAr[index][propNum][tf];
							}
						}
						queue.add(nextPropNum);
					}
					
					if (output instanceof And && (sameTurnEffectsAr[index][propNum][0] == -1 || sameTurnEffectsAr[index][propNum][1] == -1)) {
						Proposition nextProp = (Proposition) output.getSingleOutput();
						int nextPropNum = propMap.get(nextProp);
						for (int tf = 0; tf < 2; tf++) {
							if (sameTurnEffectsAr[index][propNum][tf] == -1) {
								sameTurnEffectsAr[index][nextPropNum][tf] = -1;
							}
						}
						queue.add(nextPropNum);
					}
					else if (output instanceof Or && (sameTurnEffectsAr[index][propNum][0] == 1 || sameTurnEffectsAr[index][propNum][1] == 1)) {
						Proposition nextProp = (Proposition) output.getSingleOutput();
						int nextPropNum = propMap.get(nextProp);
						for (int tf = 0; tf < 2; tf++) {
							if (sameTurnEffectsAr[index][propNum][tf] == 1) {
								sameTurnEffectsAr[index][nextPropNum][tf] = 1;
							}
						}
						queue.add(nextPropNum);
					}
					else if (output instanceof Not && (sameTurnEffectsAr[index][propNum][0] != 0 || sameTurnEffectsAr[index][propNum][1] != 0)) {
						Proposition nextProp = (Proposition) output.getSingleOutput();
						int nextPropNum = propMap.get(nextProp);
						for (int tf = 0; tf < 2; tf++) {
							if (sameTurnEffectsAr[index][propNum][tf] != 0) {
								sameTurnEffectsAr[index][nextPropNum][tf] = -sameTurnEffectsAr[index][propNum][tf];
							}
						}					
						queue.add(nextPropNum);
					}
					else if (output instanceof Transition && (sameTurnEffectsAr[index][propNum][0] != 0 || sameTurnEffectsAr[index][propNum][1] != 0)) {
						Proposition nextProp = (Proposition) output.getSingleOutput();
						int nextPropNum = propMap.get(nextProp);
						for (int tf = 0; tf < 2; tf++) {
							if (sameTurnEffectsAr[index][propNum][tf] != 0) {
								nextTurnEffectsAr[index][nextPropNum][tf] = sameTurnEffectsAr[index][propNum][tf];
							}
						}
					}
				}
			}			
		}
		// Compute nextTurnEffects
		for (int index = 0; index < numProps; index++) {
			List<Integer> queue = new LinkedList<Integer>();
			boolean[] visited = new boolean[numProps];
			for (int propNum = 0; propNum < numProps; propNum++) {
				if (nextTurnEffectsAr[index][propNum][0] != 0 || nextTurnEffectsAr[index][propNum][1] != 0) {
					queue.add(propNum);
				}
			}
			Log.println('b', "At index " + index + " " + queue + " " + propIndex[index] + " " + propIndex[index].getOutputs());
			while (!queue.isEmpty()) {
				int propNum = queue.remove(0);
				if (visited[propNum]) {
					continue;
				}
				visited[propNum] = true;
			
				for (int tf = 0; tf < 2; tf++) {
					if (nextTurnEffectsAr[index][propNum][tf] != 0) {
						int effectOnPropNum = (nextTurnEffectsAr[index][propNum][tf] == 1) ? 1 : 0; 
						for (int nextPropNum = 0; nextPropNum < numProps; nextPropNum++) {
							if(sameTurnEffectsAr[propNum][nextPropNum][effectOnPropNum] != 0) {
								nextTurnEffectsAr[index][nextPropNum][tf] = sameTurnEffectsAr[propNum][nextPropNum][effectOnPropNum];
								queue.add(nextPropNum);
							}
						}
					}
				}
			}
		}
		
		trueLatches = new ArrayList<Integer>();
		falseLatches = new ArrayList<Integer>();
		satisfiedLatches = new HashSet<Proposition>();
		relevantPropositions = new HashSet<Proposition>(this.propMap.keySet());
		
		for (int index = 0; index < numProps; index++) {
			int countSameTurn = 0;
			int countNextTurn = 0;
			StringBuilder outputSame = new StringBuilder();
			StringBuilder outputNext = new StringBuilder();
			for (int propNum = 0; propNum < numProps; propNum++) {
				if (sameTurnEffectsAr[index][propNum][0] != 0) {
					countSameTurn++;
					outputSame.append("F[" + propNum + "]=" + ( sameTurnEffectsAr[index][propNum][0] == 1 ? "T" : "F" ) + ";");
				}
				if (sameTurnEffectsAr[index][propNum][1] != 0) {
					countSameTurn++;
					outputSame.append("T[" + propNum + "]=" + ( sameTurnEffectsAr[index][propNum][1] == 1 ? "T" : "F" ) + ";");
				}
				if (propNum != index && sameTurnEffectsAr[index][propNum][0] != 0 && sameTurnEffectsAr[index][propNum][1] != 0) {
					outputSame.append("=[" + propNum + "]!;");
				}
				if (nextTurnEffectsAr[index][propNum][0] != 0) {
					countNextTurn++;
					if (propNum == index && nextTurnEffectsAr[index][propNum][0] == -1) {
						falseLatches.add(propNum);
					}
					outputNext.append("F[" + propNum + "]=" + ( nextTurnEffectsAr[index][propNum][0] == 1 ? "T" : "F" ) + ";");
				}
				if (nextTurnEffectsAr[index][propNum][1] != 0) {
					countNextTurn++;
					if (propNum == index && nextTurnEffectsAr[index][propNum][1] == 1) {
						trueLatches.add(propNum);
					}
					outputNext.append("T[" + propNum + "]=" + ( nextTurnEffectsAr[index][propNum][1] == 1 ? "T" : "F" ) + ";");
				}
			}
			Log.println('b', "Count for index " + index + " = " + countSameTurn + " same turn / " + countNextTurn + " next turn ; (" + propIndex[index].getName() + "): " + outputSame.toString() + "//" + outputNext.toString());
		}
		Log.println('l', trueLatches.size() + " true latches and " + falseLatches.size() + " false latches found");
		
	}
	*/
	
	public long multiMonte(MachineState state, int probes){
//		long start = System.currentTimeMillis();
		long sum = 0;
		if (operator instanceof NativeOperator) {
			sum = ((NativeOperator)operator).multiMonte(initBasePropositionsFromState(state), probes);
		} else {
			for (int i = 0; i < probes; i++) {
				MachineState newState = monteCarlo(state, null);
				if (newState != null) {
					try {
						sum += getGoal(newState, mainRole);
					} catch (GoalDefinitionException e) {
						i--;
					}
				} else {
					i--;
				}
			}
		}
//		long end = System.currentTimeMillis();
//		System.out.println("Monte Carlo Results: " + sum/(double)probes + "\tin " + (end-start) + " ms at " + 
//				(end-start)/(double)probes + " ms per probe");
		return sum;
	}

	
	public BooleanMachineState monteCarlo(MachineState state, int[] depth) {
		boolean[] props = initBasePropositionsFromState(state);
		int d = operator.monteCarlo(props);
		if (depth != null)
			depth[0] = d;
		return new BooleanMachineState(Arrays.copyOfRange(props, basePropStart, inputPropStart), propIndex);
	}
	
		
	@Override
	public String toString() {
		int numGoals = 0;
		for (int[][] roleGoals : goalPropMap) {
			numGoals += roleGoals.length;
		}
		return "BPNSM with " + (basePropStart - initIndex) + " init, " + (inputPropStart - basePropStart) + " base, " + (internalPropStart - inputPropStart) + " input, " + (numProps - internalPropStart) + " internal, " + numGoals + " goals, terminal = " + terminalIndex + " using " + (operator == nativeOperator ? "native" : "javassist"); 
	}
	
	/** Factoring logic */
	
	public BooleanPropNetStateMachine[] factor() {
		
		Log.println('g', "Starting factoring with " + this.toString());
		
		BooleanPropNetStateMachine referenceMachine = this;
		try {		
			Map<Proposition, List<Proposition>> lowestLevel = new HashMap<Proposition, List<Proposition>>();
			findLowestLevel(referenceMachine.propIndex[referenceMachine.terminalIndex], lowestLevel, new LinkedList<Proposition>());

			List<Factor> factors = new ArrayList<Factor>();
			for (Proposition prop : lowestLevel.keySet()) { // tentative root 
				Factor factor = new Factor(prop);
				factors.add(factor);

				// Reverse DFS
				reverseDFS(prop, factor, referenceMachine, 0);
			}

			// Merge if necessary
			for (int i = 0; i < factors.size(); i++) {
				for (int j = i + 1; j < factors.size(); j++) {
					HashSet<Proposition> copy = new HashSet<Proposition>(factors.get(i).internalProps);
					copy.retainAll(factors.get(j).internalProps);
					if (copy.size() > 0) { // Should merge

						// Find most recent ancestor
						List<Proposition> trail1 = lowestLevel.get(factors.get(i).terminalProp);
						List<Proposition> trail2 = lowestLevel.get(factors.get(j).terminalProp);
						while (!trail2.contains(trail1.get(0))) {
							trail1.remove(0);
						}
						Proposition ancestor = trail1.get(0);
						Factor newFactor = new Factor(ancestor);

						// Find all factors that have this as an ancestor
						for (int k = 0; k < factors.size(); k++) {
							if (lowestLevel.get(factors.get(k).terminalProp).contains(ancestor)) {
								lowestLevel.remove(factors.get(k).terminalProp);
								factors.remove(k);
								k--;
							}
						}
						factors.add(newFactor);
						reverseDFS(ancestor, newFactor, referenceMachine, 0);
						Log.println('g', "Merged into " + newFactor);
						// lowestLevel.put(ancestor, trail1); // Throw this back into the pool of factors

						// Hacky way to reset loop
						i = -1;
						break;
					}
				}
			}

			boolean isWellFactored = factors.size() > 1;

			if (!isWellFactored) {
				return null;
			}
			// Sanity check: assert that all base and internal propositions in each factor are exclusive
			for (int i = 0; i < factors.size(); i++) {
				for (int j = i + 1; j < factors.size(); j++) {
					HashSet<Proposition> copy = new HashSet<Proposition>(factors.get(i).internalProps);
					copy.retainAll(factors.get(j).internalProps);
					if (copy.size() != 0) {
						isWellFactored = false;
					}
					copy = new HashSet<Proposition>(factors.get(i).baseProps);
					copy.retainAll(factors.get(j).baseProps);
					if (copy.size() != 0) {
						isWellFactored = false;
					}
				}
			}

			if (!isWellFactored) {
				return null;
			}
			// Set name of each factor's root to terminal 
			for (Factor factor : factors) {
				factor.terminalProp.setName(referenceMachine.propIndex[referenceMachine.terminalIndex].getName());
			}

			// TODO By assumption, goals are disjunctively factorable and descendants of terminal

			// Add goals
			for (int role = 0; role < referenceMachine.goalPropMap.length; role++) {
				for (int[] goalProp : referenceMachine.goalPropMap[role]) {
					int[] numFactorsFound = new int[]{0};
					addGoals(referenceMachine.propIndex[goalProp[0]], referenceMachine.propIndex[goalProp[0]], factors, referenceMachine.roleIndex[role], new LinkedList<Component>(), numFactorsFound);
				}
			}
/*
			// Ensure all inputs are present first
			for (Factor factor : factors) {
				for (int role = 0; role < referenceMachine.legalPropMap.length; role++) {
					int[] legals = referenceMachine.legalPropMap[role];
					for (int legal = 0; legal < legals.length; legal++) {
						if (!factor.inputProps.contains(referenceMachine.propIndex[referenceMachine.legalInputMap[legals[legal]]])) {
							factor.inputProps.add(referenceMachine.propIndex[referenceMachine.legalInputMap[legals[legal]]]);
							factor.components.add(referenceMachine.propIndex[referenceMachine.legalInputMap[legals[legal]]]);
							factor.components.add(referenceMachine.propIndex[legals[legal]]);
						}
					}
				}
			}
*/
			// Input consolidation

			Map<Proposition, List<Proposition>> highestLevel = new HashMap<Proposition, List<Proposition>>();
			for (int inputProp = inputPropStart; inputProp < internalPropStart; inputProp++) {
				findHighestLevel(referenceMachine.propIndex[inputProp], highestLevel, referenceMachine.propIndex[inputProp]);
			}

			/*
			String temp = "";
			for (Proposition reachableProp : highestLevel.keySet()) {
				if (highestLevel.get(reachableProp).size() > 1) {
					temp += reachableProp.getName() + " in factor ";
					for (int j = 0; j < factors.size(); j++) {
						if (factors.get(j).internalProps.contains(reachableProp)) {
							temp += j;
						}
					}
					temp += " " + highestLevel.get(reachableProp).size() + ";";
				}
			}
			Log.println('g', "Input lists " + temp);
			*/

			for (Proposition reachableProp : highestLevel.keySet()) {
				if (highestLevel.get(reachableProp).size() > 1) {
					for (Factor factor : factors) {
						if (factor.internalProps.contains(reachableProp)) {
							// Keep only one path
							Component connector = reachableProp.getSingleInput();
							Component randomInputPath = connector.getInputs().iterator().next();
							for (Component input : connector.getInputs()) {
								//Log.println('g', "Removing " + input + " " + randomInputPath + " " + input.hashCode() + " in factor " + k);
								if (input == randomInputPath) {
									continue;
								}
								Component currentToHide = input;
								while (true) {
									factor.components.remove(currentToHide);
									factor.inputProps.remove(currentToHide);
									if (currentToHide.getInputs().size() != 0) {
										currentToHide = currentToHide.getSingleInput();
									}
									else {
										break;
									}
								}
								
							}
						}
					}
				}
			}

			// Add legals
			Set<Proposition> allLegals = new HashSet<Proposition>();
			for (int inputProp = referenceMachine.inputPropStart; inputProp < referenceMachine.internalPropStart; inputProp++) {
				allLegals.add(referenceMachine.propIndex[referenceMachine.legalInputMap[inputProp]]);
			}
			Log.println('h', "Filtering " + allLegals.size() + " legals");
			for (Factor factor : factors) {
				factor.components.removeAll(allLegals);
				for (Proposition inputProp : factor.inputProps) {
					// Find legal corresponding to each goal
					Proposition legalProp = referenceMachine.propIndex[referenceMachine.legalInputMap[referenceMachine.propMap.get(inputProp)]];
					reverseDFS(legalProp, factor, referenceMachine, 0);
					factor.legalInputMap.put(legalProp, inputProp);
					factor.legalInputMap.put(inputProp, legalProp);
					factor.components.add(legalProp);
				}
			}
			/*
		for (int i = 0; i < factors.size(); i++) {
			Log.println('g', "Factor " + i + " has " + factors.get(i).inputProps.size() + " inputs");
			factors.get(i).legalInputMap = referenceMachine.legalInputMap;
		}
			 */

			// TODO Fix Init hack around

			Set<Component> alreadyVisited = new HashSet<Component>();
			List<Component> toVisit = new LinkedList<Component>();
			// BFS
			int depth = 0;
			toVisit.add(referenceMachine.propIndex[referenceMachine.initIndex]);
			while (true) {
				List<Component> willVisit = new LinkedList<Component>();
				while (!toVisit.isEmpty()) {
					Component curr = toVisit.remove(0);
					/*
					int factor = -1;
					for (int i = 0; i < factors.size(); i++) {
						if (factors.get(i).components.contains(curr)) {
							factor = i;
							break;
						}
					}
					*/

					if (!alreadyVisited.contains(curr)) {
						//DEBUG  Log.println('g', String.format("%" + (depth + 1) + "s :%2d:" + (curr instanceof Proposition ? ((Proposition) curr).getName() : curr.getClass().getName()) + curr.hashCode() + " with " + curr.getInputs().size() + " inputs, " + curr.getOutputs().size() + " outputs", " ", factor));

						alreadyVisited.add(curr);
						for (Component next : curr.getOutputs()) {
							willVisit.add(next);
						}
					}
					else {
						//DEBUG  Log.println('g', String.format("%" + (depth + 1) + "s :%2d: visited " + (curr instanceof Proposition ? ((Proposition) curr).getName() : curr.getClass().getName()) + curr.hashCode() + " with " + curr.getInputs().size() + " inputs, " + curr.getOutputs().size() + " outputs", " ", factor));
					}
				}
				if (willVisit.isEmpty()) {
					break;
				}
				toVisit = willVisit;
				depth++;
			}

			// Add entire init subtree to all factors
			for (Factor factor : factors) {
				factor.components.addAll(alreadyVisited);
				for (Component comp : alreadyVisited) {
					if (comp instanceof Proposition) {
						factor.internalProps.add((Proposition) comp);
					}
				}
			}

			alreadyVisited = new HashSet<Component>();
			toVisit = new LinkedList<Component>();
			// BFS
			depth = 0;
			toVisit.add(referenceMachine.propIndex[referenceMachine.terminalIndex]);
			while (true) {
				List<Component> willVisit = new LinkedList<Component>();
				while (!toVisit.isEmpty()) {
					Component curr = toVisit.remove(0);	
					/*
					int factor = -1;
					for (int i = 0; i < factors.size(); i++) {
						if (factors.get(i).components.contains(curr)) {
							factor = i;
							break;
						}
					}
					*/

					//DEBUG  Log.println('g', String.format("%" + (depth + 1) + "s :%2d:" + (curr instanceof Proposition ? ((Proposition) curr).getName() : curr.getClass().getName()) + " with " + curr.getInputs().size() + " inputs, " + curr.getOutputs().size() + " outputs", " ", factor));

					alreadyVisited.add(curr);
					for (Component next : curr.getInputs()) {
						if (!alreadyVisited.contains(next)) {
							willVisit.add(next);
						}
					}
				}
				if (willVisit.isEmpty()) {
					break;
				}
				toVisit = willVisit;
				depth++;
			}
			Log.println('h', factors.size() + " factors found");
			
			// Generate the new statemachines
			BooleanPropNetStateMachine[] minions = new BooleanPropNetStateMachine[factors.size()];
			for (int i = 0; i < factors.size(); i++) {
				minions[i] = new CachedBooleanPropNetStateMachine(mainRole);
				minions[i].initializeFactor(factors.get(i).components, rolesList);
				minions[i].pnet.renderToFile(PNET_FOLDER + File.separator + "factor" + i + ".dot");
				if (i == 0) {
					minions[i].calculatePropEffects();
					minions[i].initOperator(true);
					StateMachineFactory.pushMachine(StateMachineFactory.CACHED_BPNSM_FACTOR, minions[i]);
				}
				Log.println('f', "Factor " + i + " : " + minions[i].toString());
				for (int role = 0; role < minions[i].legalPropMap.length; role++) {
					for (int legal = 0; legal < minions[i].legalPropMap[role].length; legal++) {
						Log.println('f', "Factor " + i + " legal " + legal + " for " + role + " : " + minions[i].propIndex[minions[i].legalPropMap[role][legal]] + " " + minions[i].legalInputMap[minions[i].legalPropMap[role][legal]]);
					}
				}
			}

			return minions;
		}
		catch (Exception ex) { // Don't let factoring break anything
			ex.printStackTrace();
			return null;
		}
	}
	
	// Has the potential to search the entire supertree of goalProp
	private void addGoals(Proposition prop, Proposition goalProp, List<Factor> factors, Role role, List<Component> trail, int[] numFactorsFound) {
		if (numFactorsFound[0] == factors.size()) {
			return;
		}
		//DEBUG  Log.println('g', "exploring " + prop.getName() + " with goal " + goalProp.getName());
		for (Factor factor : factors) {
			if (factor.internalProps.contains(prop)) { // Proposition intersects factored tree; should only happen once
				//DEBUG  Log.println('g', "Found goal " + prop.getName());
				if (!factor.goalProps.containsKey(role)) {
					factor.goalProps.put(role, new HashSet<Proposition>());
				}
				factor.goalProps.get(role).add(goalProp);
				for (Component c : trail) {
					if (c instanceof Proposition) {
						factor.internalProps.add((Proposition) c);
					}
					factor.components.add(c);
				}
				numFactorsFound[0]++;
				return; // Only one factor will contain prop
			}
		}
		if (prop.getInputs().size() == 0) {
			return;
		}
		Component comp = prop.getSingleInput();
		for (Component higherProp : comp.getInputs()) {
			List<Component> trailCopy = new LinkedList<Component>(trail);
			trailCopy.add(prop);
			trailCopy.add(comp);
			addGoals((Proposition) higherProp, goalProp, factors, role, trailCopy, numFactorsFound);
		}
	}
	
	private void reverseDFS(Proposition prop, Factor factor, BooleanPropNetStateMachine referenceMachine, int depth) {
		//DEBUG  Log.println('g', String.format("%" + (depth + 1) + "s%s", " ", "Ex: " + prop.getName() + prop.hashCode() + " " + prop.getInputs().size() + " inputs, " + prop.getOutputs().size() + " outputs, " + " input"));
		int index = referenceMachine.propMap.get(prop);
		if (index >= inputPropStart && index < internalPropStart) {
			//DEBUG  Log.println('g', String.format("%" + (depth + 1) + "s%s", " ", "Reached " + prop.getName() + prop.hashCode() + " " + prop.getInputs().size() + " inputs, " + prop.getOutputs().size() + " outputs, " + " input"));
			factor.inputProps.add(prop);
			factor.components.add(prop);
			return;
		}	
		boolean continueSearching = prop.getInputs().size() == 1;
		if (index >= basePropStart && index < inputPropStart) {
			if (factor.baseProps.add(prop)) { 
				factor.components.add(prop);
			}
			else {
				continueSearching = false;
			}
		}
		else if(!factor.internalProps.contains(prop)) {
			int numMatches = 0;
			for (Proposition p : factor.internalProps) {
				if (p.hashCode() == prop.hashCode()) {
					numMatches++;
				}
			}
			if (numMatches != 0) {
				System.err.println("Problem ");
			}
			factor.internalProps.add(prop);
			factor.components.add(prop);
		}
		else {
			continueSearching = false;
		}
		//DEBUG  Log.println('g', String.format("%" + (depth + 1) + "s%s", " ", "Reached " + prop.getName() + prop.hashCode() + " " + prop.getInputs().size() + " inputs, " + prop.getOutputs().size() + " outputs, " + (continueSearching ? "" : " stopping")));
		if (continueSearching) { // Not a spurious proposition
			Component comp = prop.getSingleInput();
			factor.components.add(comp);
			//DEBUG  Log.println('g', String.format("%" + (depth + 1) + "s%s", " ", "Expanding " + comp.getClass().getName() + comp.hashCode() + " " + comp.getInputs().size() + " inputs"));
			for (Component input : comp.getInputs()) {
				reverseDFS((Proposition)input, factor, referenceMachine, depth + 1);
			}
		}

	}
	
	// TODO Such a hack! Works only for special versions of lights on
	
	private boolean findHighestLevel(Proposition prop, Map<Proposition, List<Proposition>> level, Proposition inputProp) {
		/*
		Log.println('g', "At prop " + prop.getName());
		if (!level.containsKey(prop)) {
			level.put(prop, new LinkedList<Proposition>());
		}
		level.get(prop).add(inputProp);
		Component comp = prop.getSingleOutput();
		if (comp instanceof And || comp instanceof Transition) {
			if (comp.getOutputs().size() == 1) {
				findHighestLevel((Proposition)comp.getSingleOutput(), level, inputProp);
			} 
		} else if (comp instanceof Or) {
			for (Component input : comp.getOutputs()) {
				findHighestLevel((Proposition)input, level, inputProp);
			}
		}
		*/
		//Log.println('g', "At prop " + prop.getName());
		if (prop.getOutputs().size() == 0) {
			return false;
		}
		Component comp = prop.getSingleOutput();
		if (comp.getInputs().size() > 1) {
			Proposition disjunctiveNode = (Proposition) comp.getSingleOutput();
			if (!level.containsKey(disjunctiveNode)) {
				level.put(disjunctiveNode, new LinkedList<Proposition>());
			}
			level.get(disjunctiveNode).add(inputProp);
			return true;
		}
		if (comp instanceof And || comp instanceof Transition) {
			if (comp.getOutputs().size() == 1) {
				if (findHighestLevel((Proposition)comp.getSingleOutput(), level, inputProp))
					return true;
			} 
		} else if (comp instanceof Or) {
			for (Component output : comp.getOutputs()) {
				if (findHighestLevel((Proposition)output, level, inputProp))
					return true;
			}
		}
		return false;
		
	}
	
	private void findLowestLevel(Proposition prop, Map<Proposition, List<Proposition>> level, LinkedList<Proposition> trail) {
		Component comp = prop.getSingleInput();
		if (comp instanceof And) {
			if (comp.getInputs().size() == 1) {
				trail.add(prop);
				findLowestLevel((Proposition)comp.getSingleInput(), level, trail);
				trail.remove(prop);
			} else {
				level.put(prop, new LinkedList<Proposition>(trail));
			}
		} else if (comp instanceof Or) {
			for (Component input : comp.getInputs()) {
				trail.add(prop);
				findLowestLevel((Proposition)input, level, trail);
				trail.remove(prop);
			}
		} else {
			level.put(prop, new LinkedList<Proposition>(trail));
			
		}
	}
	
	
	private class Factor {
		public Proposition terminalProp;
		public Map<Role, Set<Proposition>> goalProps;
		public Map<Proposition, Proposition> legalInputMap;
		public Set<Proposition> baseProps;
		public Set<Proposition> inputProps;
		public Set<Component> components;
		//assert(everything will be OK) /*of course, the assert fails*/
		public Set<Proposition> internalProps;
		public Factor(Proposition terminalProp) {
			this.terminalProp = terminalProp;
			this.components = new HashSet<Component>();
			this.goalProps = new HashMap<Role, Set<Proposition>>();
			this.internalProps = new HashSet<Proposition>();
			this.baseProps = new HashSet<Proposition>();
			this.inputProps = new HashSet<Proposition>();
			this.legalInputMap = new HashMap<Proposition, Proposition>();
		}
		
		@Override
		public String toString() {
			int numGoals = 0;
			for(Role r : goalProps.keySet()) {
				numGoals += goalProps.get(r).size();
			}
			return "Factor rooted at " + terminalProp.getName() + " with " + components.size() + " components, " + internalProps.size() + " internal props, " + baseProps.size() + " base props, " + inputProps.size() + " input props, " + numGoals + " goal props";
		
		}
	}
}
