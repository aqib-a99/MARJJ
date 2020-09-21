import java.util.*;


public class MARJJ_v5 implements GinRummyPlayer {
    // Adj penalty/bonus, same rank penalty/bonus, 2 adj penalty/bonus
    public static double SEQUENCE_BONUS = -10.0; // opponent cannot make a sequence
    public static double ADJ_BONUS = -5.0; // bonus if one of the adjacent, suited cards is unavailable
    public static double ADJ2_BONUS = -3.0; // bonus if a card two away in rank and same suit is unavailable
    public static double SEQUENCE_PENALTY = 20.0; // penalty if opponent can make a sequence
    public static double ADJ_PENALTY = 5.0; // penalty if one of the adjacent, suited cards is in opponents hand
    public static double ADJ2_PENALTY = 3.0; // penalty if a card two away in rank and same suit is in opponents hand
    public static double SET_BONUS = -10.0; // bonus if opponent cannot make a sequence
    public static double RANK_BONUS = -4.0; // bonus if one other card of same rank is unavailable to opponent
    public static double SET_PENALTY = 20; // penalty if opponent can make a sequence with this card
    public static double RANK_PENALTY = 4.0; // penalty if opponent has a card of this rank

    private int playerNum;
    @SuppressWarnings("unused")
    private int startingPlayerNum;
    private ArrayList<Card> cards = new ArrayList<Card>();
    private ArrayList<Card> opponentDraws = new ArrayList<Card>();
    private Random random = new Random();
    private boolean opponentKnocked = false;
    private int turns = 0;
    private ArrayList<Card> discardedCards = new ArrayList<Card>();
    Card faceUpCard, drawnCard;
    ArrayList<Long> drawDiscardBitstrings = new ArrayList<Long>();
    GameState gameState;

    @Override
    public void startGame(int playerNum, int startingPlayerNum, Card[] cards) {
        gameState = new GameState();
        this.playerNum = playerNum;
        this.startingPlayerNum = startingPlayerNum;
        this.cards.clear();
        for (Card card : cards){
            this.cards.add(card);
            gameState.addSeen(card.getId());
        }
        opponentKnocked = false;
        drawDiscardBitstrings.clear();
        turns = 0;
    }

    @Override
    public boolean willDrawFaceUpCard(Card card) {
        // Return true if card would be a part of a meld, false otherwise.
        discardedCards.add(card);
        gameState.addSeen(card.getId());
        this.faceUpCard = card;
        @SuppressWarnings("unchecked")
        ArrayList<Card> newCards = (ArrayList<Card>) cards.clone();
        newCards.add(card);
        ArrayList<ArrayList<ArrayList<Card>>> bestMelds = GinRummyUtil.cardsToBestMeldSets(newCards);
        if (bestMelds.isEmpty()) return false;

        // TODO: Make sure not only does new card make a meld.  Also make sure that the total deadwood
        // points drop
        ArrayList<ArrayList<Card>> melds = getBestMeld(bestMelds);
        for (ArrayList<Card> meld : melds)
            if (meld.contains(card) && Helper.getBestDeadwoodAfterDiscard(newCards) < Helper.getBestDeadwood(cards)){
                return true;}


        // NOTE: With the new discard strategy, you really want to go for melds
        //if (Helper.getBestDeadwoodAfterDiscard(newCards) < Helper.getBestDeadwood(cards) && turns > 5)
        //	return true;

        return false;
    }

    @Override
    public void reportDraw(int playerNum, Card drawnCard) {

        // Ignore other player draws.  Add to cards if playerNum is this player.
        if (playerNum == this.playerNum) {
            cards.add(drawnCard);
            this.drawnCard = drawnCard;
            gameState.addSeen(drawnCard.getId());
        }

        if (drawnCard != null && playerNum != this.playerNum)
            gameState.addOpponentCards(drawnCard.getId());

        if (drawnCard != faceUpCard) {
            gameState.numCardsInPile--;
        }

    }

    @SuppressWarnings("unchecked")
    @Override
    public Card getDiscard() {
        turns++;

        // Parameters to tune
        final int MAX_DEADWOOD_DIFFERENCE = 5; // What is the maximum deadwood difference between unmelded cards
        // at which you would still want to consider cards for discards.
        // For example, if this is 6, if there was a facecard, you could also
        // consider discarding a 5, but not a 4.

        // Find cards that are not in any meld, and within MAX_DEADWOOD_DIFFERENCE of
        // the most expensive card
        ArrayList<DiscardStat> nonMelds = Helper.getUnmeldedCards(cards, faceUpCard, MAX_DEADWOOD_DIFFERENCE);

        // There is only one potential discard - Gin is possible!
        if (nonMelds.size() == 1) return nonMelds.get(0).card;

        // For each of the discards, get expected value of a discard from perspective of
        // deadwood improvement in current player
        Helper.getFutureDeadwoodImprovement(cards, gameState, nonMelds, turns);

        // For each of the discards, get the expected value of the discard from perspective of
        // deadwood improvement in other player or my ability to lay off card on opponent
        Helper.getOpponentDeadwoodImprovement(cards, nonMelds, gameState, turns);

        // Find most promising discard
        ArrayList<Card> discards = new ArrayList<>();
        double bestScore = Double.MAX_VALUE;
        for (int i = 0; i < nonMelds.size(); i++) {
            if (bestScore > nonMelds.get(i).myEV + nonMelds.get(i).oppEV) {
                bestScore = nonMelds.get(i).myEV + nonMelds.get(i).oppEV;
                discards.clear();
            }
            if (bestScore == nonMelds.get(i).myEV + nonMelds.get(i).oppEV) {
                discards.add(nonMelds.get(i).card);
            }
        }

        // Return a randomly chosen discard from collection
        return discards.get(random.nextInt(discards.size()));
    }


    // Statistics used to evalute potential discards
    static class DiscardStat {
        DiscardStat(Card card) {this.card = card; }

        Card card;
        int deadwoodPointsAfterDiscard; // The deadwood after discarding this card
        double myEV;					// The expected improvement in my EV over the game by discarding this card
        double oppEV;					// The expected improvement in opponents EV over the game by discarding this card
    }


    @Override
    public void reportDiscard(int playerNum, Card discardedCard) {
        // Ignore other player discards.  Remove from cards if playerNum is this player.
        if (playerNum == this.playerNum)
            cards.remove(discardedCard);

        if (playerNum != this.playerNum)
            gameState.addOpponentDiscards(discardedCard.getId());

        discardedCards.add(discardedCard);
    }

    @Override
    public ArrayList<ArrayList<Card>> getFinalMelds() {
        // Check if deadwood of maximal meld is low enough to go out.
        ArrayList<ArrayList<ArrayList<Card>>> bestMeldSets = GinRummyUtil.cardsToBestMeldSets(cards);
        if (Helper.getBestDeadwood(cards) < 11 && turns < 4)
            return getBestMeld(bestMeldSets);
        if (!opponentKnocked && (bestMeldSets.isEmpty() || GinRummyUtil.getDeadwoodPoints(bestMeldSets.get(0), cards) > (GinRummyUtil.MAX_DEADWOOD) - 10))
            return null;
        return bestMeldSets.isEmpty() ? new ArrayList<ArrayList<Card>>() : getBestMeld(bestMeldSets);
    }

    @Override
    public void reportFinalMelds(int playerNum, ArrayList<ArrayList<Card>> melds) {
        // Melds ignored by simple player, but could affect which melds to make for complex player.
        if (playerNum != this.playerNum)
            opponentKnocked = true;
    }

    @Override
    public void reportScores(int[] scores) {
        // Ignored by simple player, but could affect strategy of more complex player.
    }

    @Override
    public void reportLayoff(int playerNum, Card layoffCard, ArrayList<Card> opponentMeld) {
        // Ignored by simple player, but could affect strategy of more complex player.

    }

    @Override
    public void reportFinalHand(int playerNum, ArrayList<Card> hand) {
        // Ignored by simple player, but could affect strategy of more complex player.

    }

    public ArrayList<ArrayList<Card>> getBestMeld(ArrayList<ArrayList<ArrayList<Card>>> bestMeldSets) {

        if (bestMeldSets.size() == 1) return bestMeldSets.get(0);

        int layOffPntsToBeat = Integer.MAX_VALUE;
        int currentMeldToPlay = 0;

        for (int i = 0; i < bestMeldSets.size(); i++) {
            int layOffPoints = 0;

            for (ArrayList<Card> meld : bestMeldSets.get(i)) {
                if (meld.get(0).getSuit() == meld.get(1).getSuit()) {
                    if (meld.get(0).getRank() > 0 && (gameState.opponentHasCard(meld.get(0).getId()-1) || !gameState.hasBeenSeen(meld.get(0).getId()-1))) {
                        layOffPoints += Math.min(meld.get(0).getRank()+1, 10);
                    }
                    if (meld.get(meld.size()-1).getRank() < 12 && (gameState.opponentHasCard(meld.get(meld.size()-1).getId()+1) || !gameState.hasBeenSeen(meld.get(meld.size()-1).getId()+1))) {
                        layOffPoints += Math.min(meld.get(meld.size()-1).getRank()+1, 10);
                    }
                }
                else {
                    for (Card card : meld) {
                        if (gameState.opponentHasCard((card.getId()+13)%52) || !gameState.hasBeenSeen((card.getId()+13)%52))
                            layOffPoints += GinRummyUtil.getDeadwoodPoints(card);
                    }
                }
            }
            if (layOffPoints < layOffPntsToBeat) {
                layOffPntsToBeat = layOffPoints;
                currentMeldToPlay = i;
            }
        }

        return bestMeldSets.get(currentMeldToPlay);
    }


    public boolean hasPossibleStraight(long myhand, int id) {
        int cnt = 0;

        for (int i = id - 2; i < id + 1; i++) {
            cnt = 0;
            for (int j = i; j%13 < (i + 3)%13; j++) {
                if (j/13 == id/13) {
                    if ((myhand & 1L << j) != 0)
                        cnt++;
                }
            }
            if (cnt > 1) return true;
        }
        return false;
    }

    public boolean hasPair(long myhand, int id) {
        int cnt = 1;

        for (int i = (id + 13)%52; i != id; i=(i+13)%52 ) {
            if ((myhand & 1L << i) != 0)
                cnt++;
        }
        return (cnt > 1);
    }

    public static class GameState{

        public int numCardsInPile;
        public long seen;
        public long opponentCards;
        public long opponentDiscards;

        public GameState(){
            numCardsInPile = 31;
            seen = 0L;
            opponentCards = 0L;
            opponentDiscards = 0L;
        }

        public void addSeen(int x){
            seen |= 1L << x;
        }

        public void addOpponentCards(int x){
            opponentCards |= 1L << x;
        }

        public void addOpponentDiscards(int x){
            opponentDiscards |= 1L << x;
            seen |= 1L << x;
        }

        public boolean hasBeenSeen(int x){
            return ((seen & 1L << x) != 0);
        }

        public boolean opponentHasCard(int x){
            return ((opponentCards & 1L << x) != 0);
        }
    }

    @SuppressWarnings("unchecked")
    static class Helper{

        public static int getBestDeadwood(ArrayList<Card> myCards) {
            if (myCards.size() != 10) throw new IllegalArgumentException("Need 10 cards");

            ArrayList<ArrayList<ArrayList<Card>>> bestMeldConfigs = GinRummyUtil.cardsToBestMeldSets(myCards);

            return bestMeldConfigs.isEmpty() ? GinRummyUtil.getDeadwoodPoints(myCards) :
                    GinRummyUtil.getDeadwoodPoints(bestMeldConfigs.get(0),myCards);

        }

        public static int getBestDeadwoodAfterDiscard(ArrayList<Card> myCards) {
            if (myCards.size() != 11) throw new IllegalArgumentException("Need 11 cards");

            int bestDeadwoodPoints = Integer.MAX_VALUE;
            int curDeadwood = 0;

            for (Card card : myCards) {
                ArrayList<Card> cards = (ArrayList<Card>) myCards.clone();
                cards.remove(card);
                curDeadwood = getBestDeadwood(cards);
                if (curDeadwood < bestDeadwoodPoints) bestDeadwoodPoints = curDeadwood;
            }
            return bestDeadwoodPoints;
        }

        public static int numCardsToMakeGin(ArrayList<Card> myCards, ArrayList<Card> discardedCards, ArrayList<Card> opponentCards) {
            int cnt = 0;

            for (Card card : Card.allCards) {
                ArrayList<Card> cards = (ArrayList<Card>) myCards.clone();
                if (!myCards.contains(card) && !discardedCards.contains(card) && !opponentCards.contains(card)) {
                    cards.add(card);
                    if (getBestDeadwoodAfterDiscard(cards) == 0) cnt++;
                }
            }

            return cnt;
        }

        public static boolean opponentCanMakeMeld(Card card, long seen, long opponentCards){
            int cnt = 0;
            boolean pair = true;
            boolean straight = true;

            for (int i = (card.getId() + 13)%52; i != card.getId(); i=(i+13)%52 ) {
                if ((seen & 1L << i) != 0 && (opponentCards & 1L << i) == 0)
                    cnt++;
            }
            if (cnt > 1) pair = false;

            cnt = 0;

            for (int i = card.getId()-2; i < card.getId() + 3; i++) {
                if (i/13 == card.getId()/13) {
                    if ((seen & 1L << i) != 0 && (opponentCards & 1L << i) == 0)
                        cnt++;
                    else cnt = 0;
                }
                else cnt = 0;

                if (cnt > 2) {
                    straight = false;
                    break;
                }
            }
            return (pair || straight);
        }

        public static ArrayList<Card> cardsCannotMeld1(ArrayList<Card> myCards, long seen){
            long myhand = GinRummyUtil.cardsToBitstring(myCards);
            long tmp = myhand;
            ArrayList<Card> cantMeldCards = new ArrayList<>();
            int id;
            boolean pairs = true;
            boolean straight = true;

            while (tmp != 0) {
                pairs = true;
                straight = true;
                long tmp1 = tmp & (tmp-1);
                long card = tmp ^ tmp1;

                id = 0;
                while (card != 1) {
                    card = card >> 1;
                    id++;
                }

                int myCnt = 1;
                for (int i = (id + 13)%52; i != id; i=(i+13)%52 ) {
                    if ((myhand & 1L << i) != 0)
                        myCnt++;
                }
                if (myCnt > 1) {
                    int seenCnt = 1;
                    for (int i = (id + 13)%52; i != id; i=(i+13)%52 ) {
                        if ((seen & 1L << i) != 0)
                            seenCnt++;
                    }

                    if (seenCnt - myCnt > 1) pairs = false;
                }
                else pairs = false;
                if (!pairs) {
                    int notSeenCnt = 0;
                    for (int i = id - 2; i < id + 1; i++) {
                        myCnt = 0;
                        notSeenCnt = 0;
                        for (int j = i; j%13 < (i + 3)%13; j++) {
                            if (j/13 == id/13) {
                                if ((myhand & 1L << j) != 0)
                                    myCnt++;
                                else if ((seen & 1L << j) == 0)
                                    notSeenCnt++;
                            }
                        }
                        if (myCnt > 1 && notSeenCnt > 0) break;
                    }
                    if (myCnt < 2 || notSeenCnt < 1) straight = false;
                }

                if (!pairs && !straight) cantMeldCards.add(Card.getCard(id));

                tmp = tmp1;
            }
            return cantMeldCards;
        }

        public static ArrayList<Card> cardsCannotMeld2(ArrayList<Card> myCards, long seen){
            long myhand = GinRummyUtil.cardsToBitstring(myCards);
            long tmp = myhand;
            ArrayList<Card> cantMeldCards = new ArrayList<>();
            int id;
            boolean pairs = true;
            boolean straight = true;

            while (tmp != 0) {
                long tmp1 = tmp & (tmp-1);
                long card = tmp ^ tmp1;

                id = 0;
                while (card != 1) {
                    card = card >> 1;
                    id++;
                }

                int seenCnt = 1;
                for (int i = (id + 13)%52; i != id; i=(i+13)%52 ) {
                    if ((myhand & 1L << i) == 0 && (seen & 1L << i) != 0)
                        seenCnt++;

                    if (seenCnt > 2) pairs = false;
                }
                if (!pairs) {
                    int myCnt = 0;
                    for (int i = id - 2; i < id + 1; i++) {
                        myCnt = 0;
                        for (int j = i; j%13 < (i + 3)%13; j++) {
                            if (j/13 == id/13) {
                                if ((myhand & 1L << j) != 0 || (seen & 1L << j) == 0)
                                    myCnt++;
                            }
                        }
                        if (myCnt == 3) break;
                    }
                    if (myCnt < 3) straight = false;
                }

                if (!pairs && !straight) cantMeldCards.add(Card.getCard(id));

                tmp = tmp1;
            }
            return cantMeldCards;
        }

        public static int getDifferenceInDeadwood(ArrayList<Card> myhand, Card card) {
            ArrayList<Card> newHand = (ArrayList<Card>) myhand.clone();
            newHand.add(card);

            int curDeadwood = getBestDeadwood(myhand);

            ArrayList<ArrayList<Card>> allMelds = GinRummyUtil.cardsToAllMelds(newHand);
            ArrayList<ArrayList<Card>> newMelds = new ArrayList<ArrayList<Card>>();
            for (ArrayList<Card> meld : allMelds) {
                if (meld.contains(card)) newMelds.add(meld);
            }
            if (newMelds.isEmpty()) return curDeadwood - getBestDeadwoodAfterDiscard(newHand);

            int maxMeldPnts = 0;
            int maxMeld = 0;
            for (int i = 0; i < newMelds.size(); i++) {
                int pnts = GinRummyUtil.getDeadwoodPoints(newMelds.get(i));
                if (pnts > maxMeldPnts) {
                    maxMeldPnts = pnts;
                    maxMeld = i;
                }
            }

            for (int i = 0; i < newMelds.get(maxMeld).size(); i++) {
                newHand.remove(newMelds.get(maxMeld).get(i));
            }

            ArrayList<ArrayList<ArrayList<Card>>> otherMelds = GinRummyUtil.cardsToBestMeldSets(newHand);

            if (!otherMelds.isEmpty()) {
                for (ArrayList<Card> meld : otherMelds.get(0))
                    newMelds.add(meld);
            }

            for (ArrayList<Card> meld : newMelds)
                for (Card crd : meld)
                    newHand.remove(crd);

            ArrayList<Card> finalHand = (ArrayList<Card>) myhand.clone();

            if (!newHand.isEmpty()) {
                int highRank = -1;
                int idx = -1;
                for (int i = 0; i < newHand.size(); i++) {
                    if (newHand.get(i).getRank() > highRank) {
                        highRank = newHand.get(i).getRank();
                        idx = i;
                    }
                }
                finalHand.remove(newHand.get(idx));
            }

            int newDeadwood = GinRummyUtil.getDeadwoodPoints(newMelds,finalHand);

            return curDeadwood - newDeadwood;
        }

        public static int avgDeadwood(ArrayList<Card> myhand, long seen) {
            long notSeen = 0L;
            for (int i = 0; i < 52; i++) {
                notSeen |= 1L << i;
            }

            notSeen ^= seen;

            long tmp = notSeen;
            int cnt = 0;
            int totDeadwood = 0;

            while (tmp != 0) {
                long tmp1 = tmp & (tmp - 1);
                long card = tmp ^ tmp1;

                int id = 0;
                while (card != 1) {
                    card >>= 1;
                    id++;
                }

                ArrayList<Card> newHand = (ArrayList<Card>) myhand.clone();
                newHand.add(Card.getCard(id));

                totDeadwood += getBestDeadwoodAfterDiscard(newHand);
                cnt++;

                tmp = tmp1;
            }

            return totDeadwood/cnt;
        }

        // Create an array of cards from a space separated string representation
        public static Card[] getCards(String description) {
            String[] tokens = description.split(" ");
            Card[] retVal = new Card[tokens.length];
            for (int i = 0; i < tokens.length; i++) {
                retVal[i] = getCard(tokens[i]);
            }
            return retVal;
        }

        // Return the card corresponding to the string representation
        public static Card getCard(String description) {
            int suit = 0;
            while (!Card.suitNames[suit].equals(description.substring(1,2))) {
                suit++;
            }
            int rank = 0;
            while (!Card.rankNames[rank].equals(description.substring(0,1))) {
                rank++;
            }
            return Card.getCard(rank, suit);
        }

        /**
         * Return an arraylist of cards that are not part of any meld in any of
         * the best meld sets
         *
         * @param cards the current hand
         * @param maxDifference the maximum difference between the most expensive card in the returned list
         *                      and the least expensive card
         * @return the cards that do not appear in any meld subject to the maxDifference constraint
         */
        private static ArrayList<DiscardStat> getUnmeldedCards(ArrayList<Card> cards, Card faceUpCard, int maxDifference) {
            ArrayList<ArrayList<ArrayList<Card>>> bestMeldSets = GinRummyUtil.cardsToBestMeldSets(cards);

            boolean[] include = new boolean[cards.size()];
            if (bestMeldSets == null || bestMeldSets.size() == 0) {
                // No melds, so consider all cards
                for (int i = 0; i < include.length; i++) {
                    if (cards.get(i) != faceUpCard)
                        include[i] = true;
                }
            }
            else {
                // Find cards that are not in one of the best meld configs
                for (int i = 0; i < bestMeldSets.size(); i++) {
                    boolean[] inMeld = new boolean[cards.size()];
                    for (int j = 0; j < bestMeldSets.get(i).size(); j++) {
                        for (int k = 0; k < bestMeldSets.get(i).get(j).size(); k++) {
                            int index = cards.indexOf(bestMeldSets.get(i).get(j).get(k));
                            inMeld[index] = true;
                        }
                    }
                    for (int j = 0; j < include.length; j++) {
                        if (!inMeld[j] && cards.get(j) != faceUpCard)
                            include[j] = true;
                    }
                }
            }

            // Find most expensive unmelded card
            int biggestDeadwood = 0;
            for (int i = 0; i < cards.size(); i++) {
                if (include[i] && biggestDeadwood < GinRummyUtil.getDeadwoodPoints(cards.get(i))) {
                    biggestDeadwood = GinRummyUtil.getDeadwoodPoints(cards.get(i));
                }
            }

            // Exclude cards that don't meet the deadwood threshold
            for (int i = 0; i < cards.size(); i++) {
                if (include[i] && GinRummyUtil.getDeadwoodPoints(cards.get(i)) < biggestDeadwood - maxDifference) {
                    include[i] = false;
                }
            }

            // Construct possible discards
            ArrayList<DiscardStat> discards = new ArrayList<>();
            for (int i = 0; i < cards.size(); i++) {
                if (include[i]) discards.add(new DiscardStat(cards.get(i)));
            }

            // Only one card to consider, so immediately return it
            if (discards.size() == 1) return discards;

            // If no cards to consider (which should be pretty rare) then add all of them
            if (discards.size() == 0)
                for (int i = 0; i < cards.size(); i++)  {
                    if (cards.get(i) != faceUpCard)
                        discards.add(new DiscardStat(cards.get(i)));
                }

            // Go through and check the deadwood after a discard
            int bestDeadwood = Integer.MAX_VALUE;
            for (int i = 0; i < discards.size(); i++) {
                ArrayList<Card> nextHand = new ArrayList<>();
                for (int j = 0; j < cards.size(); j++) {
                    if (cards.get(j) != discards.get(i).card) {
                        nextHand.add(cards.get(j));
                    }
                }
                discards.get(i).deadwoodPointsAfterDiscard = Helper.getBestDeadwood(nextHand);

                // Check for gin
                if (discards.get(i).deadwoodPointsAfterDiscard == 0) {
                    // TODO: It may make sense to see if there are multiple ways to make Gin and
                    // choose one that is best...
                    DiscardStat discard = discards.get(i);
                    discards.clear();
                    discards.add(discard);
                    return discards;
                }
                bestDeadwood = Math.min(bestDeadwood, discards.get(i).deadwoodPointsAfterDiscard);
            }

            // Remove any discards that don't achieve the minimum deadwood drop
            // I think that this will not happen...
            for (int i = 0; i < discards.size(); i++) {
                if (discards.get(i).deadwoodPointsAfterDiscard > bestDeadwood + maxDifference) {
                    discards.remove(i);
                    i--;
                }
            }

            return discards;
        }

        // TODO: Tune these parameters to improve performance
        final static double INIT_WEIGHT = 18.0;
        final static double DECAY = 0.9;
        final static int MAX_CARDS_TO_CONSIDER = 7;


        /**
         * Estimate the expected value of the future deadwood improvement by discarding each of the cards
         *
         * @param hand the current cards
         * @param state the current game state
         * @param discards the potential discards
         */
        public static void getFutureDeadwoodImprovement(ArrayList<Card> hand, GameState state, ArrayList<DiscardStat> discards, int turns) {
            for (int i = 0; i < discards.size(); i++) {
                // Go through all unseen cards to find possible deadwood after a draw
                PriorityQueue<Integer> possibleDeadwood = new PriorityQueue<>();
                @SuppressWarnings("unchecked")
                ArrayList<Card> nextHand = (ArrayList<Card>) hand.clone();
                nextHand.remove(discards.get(i).card);
                for (int c = 0; c < 52; c++) {
                    if (!state.hasBeenSeen(c)) {
                        nextHand.add(Card.getCard(c));
                        int deadwood = Helper.getBestDeadwoodAfterDiscard(nextHand);

                        possibleDeadwood.add(deadwood);
                        nextHand.remove(nextHand.size()-1);
                    }
                }

                // To calculate the EV of deadwood, we assume the following
                // Each turn we can see 2 cards:
                //   After 1 turn and 2 cards, we take the average of the best 1/2 of possible deadwood values,
                //	 After 2 turns and 4 cards, we take the average of the best 1/4 of possible deadwood values,
                //   After 3 turns and 6 cards, we take the average of the best 1/6, and so on.
                // Note: If we have drawn a card faceup, it makes it less likely that we will see some cards.  This is
                // a limitation of this model
                double aveFutureDeadwood = 0.0;
                int count = 0;
                while (count < MAX_CARDS_TO_CONSIDER && possibleDeadwood.size() > 0) {
                    count++;
                    aveFutureDeadwood += possibleDeadwood.remove();
                }
                if (count > 0) aveFutureDeadwood /= count;
                discards.get(i).myEV = discards.get(i).deadwoodPointsAfterDiscard + INIT_WEIGHT * aveFutureDeadwood * Math.pow(DECAY,turns-1);
            }
        }

        // For each of the discards, get the expected value of the discard from perspective of
        // deadwood improvement in other player or my ability to lay off card on opponent
        public static void getOpponentDeadwoodImprovement(ArrayList<Card> myHand, ArrayList<DiscardStat> nonMelds, GameState state, int turns) {
            ArrayList<Card> oppHand = GinRummyUtil.bitstringToCards(state.opponentCards);
            long oppCards = state.opponentCards;
            long myCards = GinRummyUtil.cardsToBitstring(myHand);
            long seen = state.seen;

            // don't discard if opponent has card of the same rank, unless they discarded one already
            // Not sure if this part is correct, It looks right to me, but I still need to test and
            // step through it to makesure:
            for (int i = 0; i < nonMelds.size(); i++) {
                Card card = nonMelds.get(i).card;
                boolean oppHigherAdjSuited = false; 	// higher adj suited card held by opponent
                boolean unavailHigherAdjSuited = false;	// higher adj suited card held by me or unavailable
                boolean oppLowerAdjSuited = false;		// lower adj suited card held by opponent
                boolean unavailLowerAdjSuited = false;	// lower adj suited card held by me or unavailable
                int oppSameRankCount = 0;				// count of same rank held by opponent
                int unavailSameRankCount = 0;			// count of same suit (other than this card)held by me or unavailable
                boolean opp2HigherAdjSuited = false;	// Second higher adj suited card held by opponent
                boolean unavail2HigherAdjSuited = false;// Second higher adj suited card held by me or unavailable
                boolean opp2LowerAdjSuited = false;		// Second lower adj suited card held by opponent
                boolean unavail2LowerAdjSuited = false;	// Second lower adj suited card held by me or unavailable

                // Check higher, adjacent suited card
                if (card.getRank() < 12) {
                    long otherCard = 1L << (card.getId() + 1);
                    if ((otherCard & oppCards) != 0) oppHigherAdjSuited = true;
                    else if ((otherCard & myCards) != 0 || (otherCard & seen) != 0) unavailHigherAdjSuited = true;

                }
                else {
                    unavailHigherAdjSuited = true;
                }
                // Check lower, adjacent suited card
                if (card.getRank() > 0) {
                    long otherCard = 1L << (card.getId() - 1);
                    if ((otherCard & oppCards) != 0) oppLowerAdjSuited = true;
                    else if ((otherCard & myCards) != 0 || (otherCard & seen) != 0) unavailLowerAdjSuited = true;

                }
                else {
                    unavailLowerAdjSuited = true;
                }

                // Check second higher, adjacent suited card
                if (card.getRank() < 11) {
                    long otherCard = 1L << (card.getId() + 2);
                    if ((otherCard & oppCards) != 0) opp2HigherAdjSuited = true;
                    else if ((otherCard & myCards) != 0 || (otherCard & seen) != 0) unavail2HigherAdjSuited = true;

                }
                else {
                    unavailHigherAdjSuited = true;
                }
                // Check second lower, adjacent suited card
                if (card.getRank() > 1) {
                    long otherCard = 1L << (card.getId() - 2);
                    if ((otherCard & oppCards) != 0) opp2LowerAdjSuited = true;
                    else if ((otherCard & myCards) != 0 || (otherCard & seen) != 0) unavail2LowerAdjSuited = true;

                }
                else {
                    unavail2LowerAdjSuited = true;
                }

                // Check same rank
                for (int suit = 0; suit < 4; suit++) {
                    long otherCard = 1L << (card.getRank() + suit * 13);
                    if ((otherCard & oppCards) != 0) oppSameRankCount++;
                    else if ((otherCard & myCards) != 0 || (otherCard & seen) != 0) unavailSameRankCount++;
                }

                // Apply bonuses/penalties
                if ((unavailHigherAdjSuited && (unavailLowerAdjSuited || unavail2LowerAdjSuited))||
                        (unavailLowerAdjSuited && (unavailHigherAdjSuited || unavail2HigherAdjSuited))) {
                    nonMelds.get(i).oppEV += SEQUENCE_BONUS;
                }
                else {
                    if (unavailLowerAdjSuited ) {
                        nonMelds.get(i).oppEV += ADJ_BONUS;
                    }
                    if (unavailHigherAdjSuited ) {
                        nonMelds.get(i).oppEV += ADJ_BONUS;
                    }
                    if (unavail2LowerAdjSuited) {
                        nonMelds.get(i).oppEV += ADJ2_BONUS;
                    }
                    if (unavail2HigherAdjSuited) {
                        nonMelds.get(i).oppEV += ADJ2_BONUS;
                    }
                }

                if ((oppHigherAdjSuited && (oppLowerAdjSuited || opp2LowerAdjSuited))||
                        (oppLowerAdjSuited && (oppHigherAdjSuited || opp2HigherAdjSuited))) {
                    nonMelds.get(i).oppEV += SEQUENCE_PENALTY;
                }
                else {
                    if (oppLowerAdjSuited ) {
                        nonMelds.get(i).oppEV += ADJ_PENALTY;
                    }
                    if (oppHigherAdjSuited ) {
                        nonMelds.get(i).oppEV += ADJ_PENALTY;
                    }
                    if (opp2LowerAdjSuited) {
                        nonMelds.get(i).oppEV += ADJ2_PENALTY;
                    }
                    if (opp2HigherAdjSuited) {
                        nonMelds.get(i).oppEV += ADJ2_PENALTY;
                    }
                }

                if (unavailSameRankCount >= 3) {
                    nonMelds.get(i).oppEV += SET_BONUS;
                }
                else if (unavailSameRankCount == 2) {
                    nonMelds.get(i).oppEV += RANK_BONUS;
                }
                if (oppSameRankCount >= 2) {
                    nonMelds.get(i).oppEV += SET_PENALTY;
                }
                else if (unavailSameRankCount == 1) {
                    nonMelds.get(i).oppEV += RANK_PENALTY;
                }
            }
        }

    }


    // For testing purposes...
    public static void main(String[] args) {
        MARJJ_v5 test = new MARJJ_v5();

        System.out.println("Scenario 2 from page 2 of review");
        test.startGame(0,0,Helper.getCards("QC QD QS AS 3H 5D 8H 8S TS JS JH"));
        ArrayList<DiscardStat> nonMelds = Helper.getUnmeldedCards(test.cards, null, 5);
        Helper.getFutureDeadwoodImprovement(test.cards, test.gameState, nonMelds, test.turns);
        Helper.getOpponentDeadwoodImprovement(test.cards, nonMelds, test.gameState, test.turns);

        for (DiscardStat ds: nonMelds) {
            System.out.println(ds.card + " Deadwood: " + ds.deadwoodPointsAfterDiscard + " EV: " + ds.myEV + " oppEV: " + ds.oppEV);
        }


        System.out.println("\nScenario 3 from top of page 3 of review");
        test.startGame(0,0,Helper.getCards("7S, 9S, 2S, KD, 9D, TS, 9C, 8D, TD, 4C"));

        test.reportDraw(1,Helper.getCard("3C")); //3C
        test.reportDiscard(1,Helper.getCard("7C")); // 7C
        test.reportDraw(0, Helper.getCard("KC")); // KC
        nonMelds = Helper.getUnmeldedCards(test.cards, null, 5);
        Helper.getFutureDeadwoodImprovement(test.cards, test.gameState, nonMelds, test.turns);
        Helper.getOpponentDeadwoodImprovement(test.cards, nonMelds, test.gameState, test.turns);

        for (DiscardStat ds: nonMelds) {
            System.out.println(ds.card + " Deadwood: " + ds.deadwoodPointsAfterDiscard + " EV: " + ds.myEV + " oppEV: " + ds.oppEV);
        }


        System.out.println("\nScenario 4 from middle of page 3 of review");
        test.reportDiscard(0,Helper.getCard("KD"));

        test.reportDraw(1,null);
        test.reportDiscard(1,Helper.getCard("QS"));

        test.reportDraw(0,Helper.getCard("5H"));
        test.reportDiscard(0,Helper.getCard("KC"));

        test.reportDraw(1,null);
        test.reportDiscard(1,Helper.getCard("QH"));

        test.reportDraw(0,Helper.getCard("4D"));
        nonMelds = Helper.getUnmeldedCards(test.cards, null, 5);
        Helper.getFutureDeadwoodImprovement(test.cards, test.gameState, nonMelds, test.turns);
        Helper.getOpponentDeadwoodImprovement(test.cards, nonMelds, test.gameState, test.turns);

        for (DiscardStat ds: nonMelds) {
            System.out.println(ds.card + " Deadwood: " + ds.deadwoodPointsAfterDiscard + " EV: " + ds.myEV + " oppEV: " + ds.oppEV);
        }


        System.out.println("\nScenario 5 from bottom of page 3 of review");
        test.reportDiscard(0,Helper.getCard("TS"));

        test.reportDraw(1,null);
        test.reportDiscard(1,Helper.getCard("QC"));

        test.reportDraw(0,Helper.getCard("8C"));
        nonMelds = Helper.getUnmeldedCards(test.cards, null, 5);
        Helper.getFutureDeadwoodImprovement(test.cards, test.gameState, nonMelds, test.turns);
        Helper.getOpponentDeadwoodImprovement(test.cards, nonMelds, test.gameState, test.turns);

        for (DiscardStat ds: nonMelds) {
            System.out.println(ds.card + " Deadwood: " + ds.deadwoodPointsAfterDiscard + " EV: " + ds.myEV + " oppEV: " + ds.oppEV);
        }

    }

}
