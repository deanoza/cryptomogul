package za.cryptomogul.test.strategies.highfrequency;

public class OrderbookScore {
	private OrderbookScoreItem buyPressureScore;
	private OrderbookScoreItem sellPressureScore;
	
	public OrderbookScoreItem getBuyPressureScore() {
		return buyPressureScore;
	}
	public void setBuyPressureScore(OrderbookScoreItem buyPressureScore) {
		this.buyPressureScore = buyPressureScore;
	}
	public OrderbookScoreItem getSellPressureScore() {
		return sellPressureScore;
	}
	public void setSellPressureScore(OrderbookScoreItem sellPressureScore) {
		this.sellPressureScore = sellPressureScore;
	}

}
