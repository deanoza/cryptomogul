package za.cryptomogul.test.strategies.highfrequency;

import java.util.Calendar;

public class OrderbookScoreItem {
	double scoreAmount;
	Calendar dateTimeStamp;

	public OrderbookScoreItem(double scoreAmount, Calendar dateTimeStamp) {
		super();
		this.scoreAmount = scoreAmount;
		this.dateTimeStamp = dateTimeStamp;
	}

	public double getScoreAmount() {
		return scoreAmount;
	}

	public void setScoreAmount(double scoreAmount) {
		this.scoreAmount = scoreAmount;
	}

	public Calendar getDateTimeStamp() {
		return dateTimeStamp;
	}

	public void setDateTimeStamp(Calendar dateTimeStamp) {
		this.dateTimeStamp = dateTimeStamp;
	}
}
