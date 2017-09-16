package za.cryptomogul.test.strategies.highfrequency;

import java.io.IOException;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.knowm.xchange.Exchange;
import org.knowm.xchange.ExchangeFactory;
import org.knowm.xchange.bittrex.BittrexExchange;
import org.knowm.xchange.bittrex.dto.marketdata.BittrexChartData;
import org.knowm.xchange.bittrex.service.BittrexChartDataPeriodType;
import org.knowm.xchange.bittrex.service.BittrexMarketDataService;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.marketdata.OrderBook;
import org.knowm.xchange.dto.trade.LimitOrder;
import org.knowm.xchange.service.marketdata.MarketDataService;

public class OrderbookRSIStrategy {

	/*
	 * The foundation of this strategy is to use RSI for overbought/oversold signals
	 * to trigger a potential buy/sell Once triggered, we need two things to happen
	 * for confirmation : RSI needs to move back in the other direction + The order
	 * book needs to support this decision. For now many of these values are
	 * hardcoded but in future the intention will be to run simulations to establish
	 * optimal settings.
	 */

	public OrderbookRSIStrategy() {
		super();
	}

	private Double openingBalance;

	private Double currentBalance;

	private CurrencyPair currencyPair = CurrencyPair.ETH_BTC;

	private String currencyPairString = "BTC-ETH";

	private Double tradingPercentage = 70.0; // Trades will be this percentage of current balance

	private Double buyTresholdBase = 15.0; // As a percentage, buy orders needs to be stronger than sell orders by this
											// percentage
	private Double sellTresholdBase = 15.0; // As a percentage, sell orders needs to be stronger than buy orders by this
											// percentage

	private Double orderBookBandSize = 5.0; // Only prices within a percentage of this value on the order book get used

	private Integer orderBookCheckInterval = 85; // In seconds

	private Integer orderBookPeriods = 7; // Use the average of the last number of order book periods in calculation,
											// order
											// books are incredibly volatile and this is intended to smooth that out
											// somewhat.

	private Integer rsiNumberOfPeriods = 14; // Number of periods to use for RSI calculation

	private Integer rsiRecheckInterval = 2; // How often to recheck RSI - in minutes

	private BittrexChartDataPeriodType rsiPeriod = BittrexChartDataPeriodType.FIVE_MIN; // Period interval used for
																						// RSI calculation
	private Integer rsiOverbought = 70;
	private Integer rsiOverboughtConfirm = 67;
	private Integer rsiOverboughtConfirmExpired = 58;

	private Integer rsiOversold = 30;
	private Integer rsiOversoldConfirm = 33;
	private Integer rsiOversoldConfirmExpired = 42;

	private Double rsiValue = null;

	private LinkedList<Double> recentBuyScores = new LinkedList<Double>();
	private LinkedList<Double> recentSellScores = new LinkedList<Double>();

	private Exchange exchange;

	private BittrexMarketDataService marketDataService;

	private boolean openBuyOrder = false;
	private boolean openSellOrder = false;

	private SignalStatus signalStatus = SignalStatus.NONE;

	public void startBot() {
		initialise();
		createRsiThread();
		createOrderBookThread();
	}

	private void createOrderBookThread() {
		Thread thread = new Thread() {
			public void run() {
				try {
					checkOrderBook();
				} catch (Exception e) {
					System.out.println("An unexpected exception has occurred during RSI calculation");
					e.printStackTrace();
				}
			}

		};

		thread.start();

	}

	private void createRsiThread() {

		Thread thread = new Thread() {
			public void run() {
				try {
					calculateRSI();
				} catch (Exception e) {
					System.out.println("An unexpected exception has occurred during RSI calculation");
					e.printStackTrace();
				}
			}

		};

		thread.start();

	}

	private void checkOrderBook() throws IOException, InterruptedException {
		OrderBook orderbook = marketDataService.getOrderBook(currencyPair);
		BigDecimal lastPrice = marketDataService.getBittrexTicker(currencyPairString).getLast();
		double bandPriceAdjustment = lastPrice.doubleValue() * (orderBookBandSize / 100);
		System.out.println("Last price:" + lastPrice);
		System.out.println("Band adjust:" + bandPriceAdjustment);

		double totalBuyPressureScore = calculateBuyPressureScore(orderbook, lastPrice, bandPriceAdjustment);
		double totalSellPressureScore = calculateSellPressureScore(orderbook, lastPrice, bandPriceAdjustment);

		System.out.println("totalBuyPressureScore:" + totalBuyPressureScore);
		System.out.println("totalSellPressureScore:" + totalSellPressureScore);

		if (recentBuyScores.size() == orderBookPeriods) {
			recentBuyScores.removeFirst();
		}
		if (recentSellScores.size() == orderBookPeriods) {
			recentSellScores.removeFirst();
		}
		recentBuyScores.add(totalBuyPressureScore);
		recentSellScores.add(totalSellPressureScore);

		if ((recentBuyScores.size() == orderBookPeriods) && (recentSellScores.size() == orderBookPeriods)) {
			double averagedBuyPressureScore = calculateAverageScore(recentBuyScores);
			double averagedSellPressureScore = calculateAverageScore(recentSellScores);
			System.out.println("averagedBuyPressureScore:" + averagedBuyPressureScore);
			System.out.println("averagedSellPressureScore:" + averagedSellPressureScore);

			if (signalStatus.equals(SignalStatus.CONFIRM_BUY)) {
				if (averagedBuyPressureScore > (averagedSellPressureScore
						+ (averagedSellPressureScore * (buyTresholdBase / 100)))) {
					if (!openBuyOrder) {
						System.out.println("Executing buy order@price:" + lastPrice);
						openBuyOrder = true;
						openSellOrder = false;
					}
				}
			}

			if (signalStatus.equals(SignalStatus.CONFIRM_SELL)) {
				if (averagedSellPressureScore > (averagedBuyPressureScore
						+ (averagedBuyPressureScore * (sellTresholdBase / 100)))) { // TODO: Adjust treshold by RSI
					if (!openSellOrder) {
						System.out.println("Executing sell order@price:" + lastPrice);
						openSellOrder = true;
						openBuyOrder = false;
					}
				}
			}
		}

		TimeUnit.SECONDS.sleep(orderBookCheckInterval);

		checkOrderBook();

	}

	private double calculateAverageScore(LinkedList<Double> recentScores) {
		double total = 0;
		for (Double amount : recentScores) {
			total += amount;
		}
		return total / recentScores.size();
	}

	private double calculateSellPressureScore(OrderBook orderbook, BigDecimal lastPrice, double bandPriceAdjustment) {
		double total = 0;
		double maxPrice = lastPrice.doubleValue() + bandPriceAdjustment;
		System.out.println("maxPrice sellPressure:" + maxPrice);
		for (LimitOrder order : orderbook.getAsks()) {
			if (order.getLimitPrice().doubleValue() < maxPrice) {
				double exponentialPercModifier = ((maxPrice - order.getLimitPrice().doubleValue())
						/ bandPriceAdjustment);
				double scoreAmount = order.getTradableAmount().doubleValue() * exponentialPercModifier;

				total += scoreAmount;
			}
		}

		return total;
	}

	private double calculateBuyPressureScore(OrderBook orderbook, BigDecimal lastPrice, double bandPriceAdjustment) {
		double total = 0;
		double minPrice = lastPrice.doubleValue() - bandPriceAdjustment;
		System.out.println("minPrice buyPressure:" + minPrice);
		for (LimitOrder order : orderbook.getBids()) {
			if (order.getLimitPrice().doubleValue() > minPrice) {
				double exponentialPercModifier = ((order.getLimitPrice().doubleValue() - minPrice)
						/ bandPriceAdjustment);
				double scoreAmount = order.getTradableAmount().doubleValue() * exponentialPercModifier;
				total += scoreAmount;
			}
		}
		total = total + (total * (orderBookBandSize / 100)); // Buy orders will be lower in value than sell orders, this
																// is done to normalise for comparison purposes
		return total;
	}

	private void calculateRSI() throws IOException, InterruptedException { // See
																			// http://www.investopedia.com/terms/r/rsi.asp
		ArrayList<BittrexChartData> chartData = null;
		try {
			chartData = marketDataService.getBittrexChartData(currencyPair, rsiPeriod);
		} catch (Exception e) {
			System.out.println("An unexpected exception has occurred during the bittrex api call");
			e.printStackTrace();
			TimeUnit.MINUTES.sleep(rsiRecheckInterval);
		}
		List<BittrexChartData> chartDataTrimmed = chartData.subList(Math.max(chartData.size() - rsiNumberOfPeriods, 0),
				chartData.size());
		Double averageUpPeriodGain = getUpPeriodGain(chartDataTrimmed);
		Double averageDownPeriodLoss = getDownPeriodLoss(chartDataTrimmed);
		Double relativeStrength = averageUpPeriodGain / averageDownPeriodLoss;
		rsiValue = 100 - 100 / (1 + relativeStrength);
		System.out.println("** RSI value : [ " + rsiValue + " ] **");

		if (rsiValue <= rsiOversold) {
			signalStatus = SignalStatus.TRIGGER_BUY;
		}

		if (rsiValue >= rsiOverbought) {
			signalStatus = SignalStatus.TRIGGER_SELL;
		}

		if ((signalStatus.equals(SignalStatus.TRIGGER_BUY)) && (rsiValue >= rsiOversoldConfirm)) {
			signalStatus = SignalStatus.CONFIRM_BUY;
		}

		if ((signalStatus.equals(SignalStatus.TRIGGER_SELL)) && (rsiValue <= rsiOverboughtConfirm)) {
			signalStatus = SignalStatus.CONFIRM_SELL;
		}
		
		if (signalStatus.equals(SignalStatus.TRIGGER_BUY) && (rsiValue >= rsiOversoldConfirmExpired)) {
			signalStatus = SignalStatus.NONE;
		}

		if (signalStatus.equals(SignalStatus.TRIGGER_SELL) && (rsiValue <= rsiOverboughtConfirmExpired)) {
			signalStatus = SignalStatus.NONE;
		}

		System.out.println("Signal Status:" + signalStatus);
		TimeUnit.MINUTES.sleep(rsiRecheckInterval);
		calculateRSI();

	}

	private Double getUpPeriodGain(List<BittrexChartData> chartData) {
		Double total = 0.00000001;

		for (BittrexChartData chartElement : chartData) {
			if (chartElement.getClose().doubleValue() > chartElement.getOpen().doubleValue()) {
				total += (chartElement.getClose().doubleValue() - chartElement.getOpen().doubleValue());
			}
		}

		return total / rsiNumberOfPeriods;
	}

	private Double getDownPeriodLoss(List<BittrexChartData> chartData) {
		Double total = 0.00000001;

		for (BittrexChartData chartElement : chartData) {
			if (chartElement.getOpen().doubleValue() > chartElement.getClose().doubleValue()) {

				total += (chartElement.getOpen().doubleValue() - chartElement.getClose().doubleValue());
			}
		}

		return total / rsiNumberOfPeriods;
	}

	private void initialise() {
		exchange = ExchangeFactory.INSTANCE.createExchange(BittrexExchange.class.getName());
		marketDataService = (BittrexMarketDataService) exchange.getMarketDataService();
		currentBalance = openingBalance;

	}

}
