package player.gamer.statemachine.eggplant;

import java.util.HashMap;
import java.util.List;

import player.gamer.statemachine.StateMachineGamer;
import util.statemachine.MachineState;
import util.statemachine.Move;
import util.statemachine.Role;
import util.statemachine.StateMachine;
import util.statemachine.exceptions.GoalDefinitionException;
import util.statemachine.exceptions.MoveDefinitionException;
import util.statemachine.exceptions.TransitionDefinitionException;
import util.statemachine.implementation.prover.cache.CachedProverStateMachine;
import apps.player.config.ConfigPanel;
import apps.player.detail.DetailPanel;

public class EggplantPrimaryGamer extends StateMachineGamer {
	protected int statesSearched;
	protected int leafNodesSearched;
	protected int cacheHits, cacheMisses;
	protected EggplantConfigPanel config = new EggplantConfigPanel();
	private HashMap<MachineState, CacheValue> keptCache;
	protected ExpansionEvaluator expansionEvaluator;
	protected HeuristicEvaluator heuristicEvaluator;
	protected ValuedMove bestWorkingMove;
	protected int maxDepth;

	private final long GRACE_PERIOD = 200;

	// TODO: Hashcode is NOT overridden by GDLSentence - this will only check if
	// the sentences are actually the same objects in memory

	@Override
	public void stateMachineMetaGame(long timeout) throws TransitionDefinitionException, MoveDefinitionException, GoalDefinitionException {
		// initialize cache, evaluators
		keptCache = new HashMap<MachineState, CacheValue>();
		expansionEvaluator = new DepthLimitedExpansionEvaluator(10);
		heuristicEvaluator = new MobilityHeuristicEvaluator();

		// try {
		// memoizedAlphaBeta(getStateMachine(), getCurrentState(), getRole(), 0,
		// 100, Integer.MIN_VALUE, getCache(), timeout - 50, false);
		// } catch(TimeUpException e){}
	}

	// TODO: Hashcode is NOT overridden by GDLSentence - this will only check if
	// the sentences are actually the same objects in memory

	@Override
	public Move stateMachineSelectMove(long timeout) throws TransitionDefinitionException, MoveDefinitionException, GoalDefinitionException {
		long start = System.currentTimeMillis();

		leafNodesSearched = statesSearched = 0;
		cacheHits = cacheMisses = 0;

		bestWorkingMove = new ValuedMove(-1, getStateMachine().getRandomMove(getCurrentState(), getRole()));
		maxDepth = 1;

		try {
			iterativeDeepening(getStateMachine(), getCurrentState(), getRole(), 0, 100, timeout - GRACE_PERIOD);
		} catch (TimeUpException ex) {
		}

		long stop = System.currentTimeMillis();
		notifyObservers(new EggplantMoveSelectionEvent(bestWorkingMove.move, bestWorkingMove.value, stop - start, statesSearched, leafNodesSearched,
				cacheHits, cacheMisses));
		return bestWorkingMove.move;
	}

	private void iterativeDeepening(StateMachine machine, MachineState state, Role role, int alpha, int beta, long endTime)
			throws MoveDefinitionException, TransitionDefinitionException, GoalDefinitionException, TimeUpException {
		// be courteous: if we only have one move and don't use data from
		// previous searches, bunt
		// TODO: Remove this once we care about the PV
		if (machine.getLegalMoves(state, role).size() == 1) {
			bestWorkingMove = new ValuedMove(-2, machine.getRandomMove(state, role));
			return;
		}
		for (int depth = 1; depth <= maxDepth; depth++) {
			expansionEvaluator = new DepthLimitedExpansionEvaluator(depth);
			int alreadySearched = statesSearched;
			bestWorkingMove = memoizedAlphaBeta(machine, state, role, alpha, beta, 0, new HashMap<MachineState, CacheValue>(), endTime, bestWorkingMove, false);
			System.out.println("After depth " + depth + "; best = " + bestWorkingMove + " searched " + (statesSearched - alreadySearched)
					+ " new states");
		}
	}

	private ValuedMove memoizedAlphaBeta(StateMachine machine, MachineState state, Role role, int alpha, int beta, int depth,
			HashMap<MachineState, CacheValue> cache, long endTime, ValuedMove primary, boolean debug) throws MoveDefinitionException, TransitionDefinitionException,
			GoalDefinitionException, TimeUpException {
		if (System.currentTimeMillis() > endTime)
			throw new TimeUpException();
		if (cache != null) {
			if (cache.containsKey(state)) {
				CacheValue cached = cache.get(state);
				if (alpha >= cached.alpha && beta <= cached.beta) {
					if (debug)
						System.out.println("Cache hit: " + cached);
					cacheHits++;
					return cached.valuedMove;
				} else {
					// Alpha-beta bounds are incompatible
					// System.out.println("Alpha: " + alpha + "\tBeta: " + beta
					// + "\tCached Alpha: " + cached.alpha + "\tCached Beta: " +
					// cached.beta);
				}
			}
			cacheMisses++;
			ValuedMove result = alphaBeta(machine, state, role, alpha, beta, depth, cache, endTime, primary, debug);
			if (debug) {
				System.out.println("AlphaBeta returned with " + result + " " + state + " " + cache);
			}
			if (result.move != null)
				cache.put(state, new CacheValue(result, alpha, beta));
			return result;
		} else {
			return alphaBeta(machine, state, role, alpha, beta, depth, cache, endTime, primary, debug);
		}
	}

	private ValuedMove alphaBeta(StateMachine machine, MachineState state, Role role, int alpha, int beta, int depth,
			HashMap<MachineState, CacheValue> cache, long endTime, ValuedMove primary, boolean debug) throws MoveDefinitionException, TransitionDefinitionException,
			GoalDefinitionException, TimeUpException {
		statesSearched++;
		if (debug)
			System.out.println("At depth " + depth + "; searched " + statesSearched + "; searching " + state);
		if (machine.isTerminal(state)) {
			leafNodesSearched++;
			return new ValuedMove(machine.getGoal(state, role), null);
		}

		if (depth > maxDepth) {
			maxDepth = depth;
		}

		if (!expansionEvaluator.eval(machine, state, role, alpha, beta, depth)) { // expansion
																					// should
																					// stop
			if (debug)
				System.out.println("Stopping expanding at depth " + depth);
			return new ValuedMove(heuristicEvaluator.eval(machine, state, role, alpha, beta, depth), null);
		}
		ValuedMove maxMove = new ValuedMove(-1, null);
		List<Move> possibleMoves = machine.getLegalMoves(state, role);
		// Collections.shuffle(possibleMoves); // TODO: Remove this line
		int pmsize = possibleMoves.size();
		if (heuristicEvaluator instanceof MobilityTracker && pmsize > 1) {
			((MobilityTracker) heuristicEvaluator).updateAverage(pmsize);
		}
		if (debug)
			System.out.println("At depth " + depth + "; searched " + statesSearched + "; moves: " + possibleMoves);
		
		//search best move first
		if (primary != null) {
			if (possibleMoves.remove(primary.move)){
				possibleMoves.add(0, primary.move);
			}
		}
		for (Move move : possibleMoves) {
			List<List<Move>> jointMoves = machine.getLegalJointMoves(state, role, move);
			int min = 100;
			int newBeta = beta;
			for (List<Move> jointMove : jointMoves) {
				MachineState nextState = machine.getNextState(state, jointMove);
				int value = memoizedAlphaBeta(machine, nextState, role, alpha, newBeta, depth + 1, cache, endTime, null, debug).value;
				if (value < min) {
					min = value;
					if (min <= alpha)
						break;
					if (min < newBeta)
						newBeta = min;
				}
			}
			if (min > maxMove.value) {
				maxMove.value = min;
				maxMove.move = move;
				if (maxMove.value >= beta)
					break;
				if (maxMove.value > alpha)
					alpha = maxMove.value;
			}
		}
		return maxMove;
	}

	@Override
	public StateMachine getInitialStateMachine() {
		return new CachedProverStateMachine();
	}

	@Override
	public DetailPanel getDetailPanel() {
		return new EggplantDetailPanel();
	}

	@Override
	public ConfigPanel getConfigPanel() {
		return config;
	}

	private HashMap<MachineState, CacheValue> getCache() {
		HashMap<MachineState, CacheValue> cache = null;
		if (config.useCache()) {
			cache = new HashMap<MachineState, CacheValue>();
			// cache = keptCache;
		}
		return cache;
	}

	// TODO: Hashcode is NOT overridden by GDLSentence - this will only check if
	// the sentences are actually the same objects in memory

	@Override
	public String getName() {
		return "EGGPLANT";
	}

}