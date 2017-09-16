package za.cryptomogul.test.strategies.highfrequency;

import java.util.Date;

public class BuySellEvent {
	private TradeType trade;

	private Long amount;
	
	private Long rate;
	
	private String pair;

	private Date dateTimeStamp;

	public TradeType getTrade() {
		return trade;
	}

	public void setTrade(TradeType trade) {
		this.trade = trade;
	}

	public Long getAmount() {
		return amount;
	}

	public void setAmount(Long amount) {
		this.amount = amount;
	}

	public Date getDateTimeStamp() {
		return dateTimeStamp;
	}

	public void setDateTimeStamp(Date dateTimeStamp) {
		this.dateTimeStamp = dateTimeStamp;
	}

	public Long getRate() {
		return rate;
	}

	public void setRate(Long rate) {
		this.rate = rate;
	}

	public String getPair() {
		return pair;
	}

	public void setPair(String pair) {
		this.pair = pair;
	}

}
