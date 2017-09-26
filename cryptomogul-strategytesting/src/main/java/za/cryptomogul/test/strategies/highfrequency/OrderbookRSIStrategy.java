package za.cryptomogul.test.strategies.highfrequency;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import org.knowm.xchange.Exchange;
import org.knowm.xchange.ExchangeFactory;
import org.knowm.xchange.bittrex.BittrexExchange;
import org.knowm.xchange.bittrex.dto.marketdata.BittrexChartData;
import org.knowm.xchange.bittrex.service.BittrexChartDataPeriodType;
import org.knowm.xchange.bittrex.service.BittrexMarketDataService;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.marketdata.OrderBook;
import org.knowm.xchange.dto.trade.LimitOrder;

public class OrderbookRSIStrategy {

	/*
	 * The foundation of this strategy is to use RSI for overbought/oversold signals
	 * to trigger a potential buy/sell Once triggered, we need two things to happen
	 * for confirmation : RSI needs to move back in the other direction + The order
	 * book needs to support this decision. For now many of these values are
	 * hardcoded but in future the intention will be to run simulations to establish
	 * optimal settings.
	 *//**
		 * Hello world!
		 *
		 */

	public OrderbookRSIStrategy(CurrencyPair currencyPair, String currencyPairString) throws IOException {
		this.currencyPairString = currencyPairString;
		this.currencyPair = currencyPair;
		String fileName = currencyPairString + "-" + "test" + ".log";
		initialiseLogger(fileName);

	}

	private void initialiseLogger(String fileName) {
		logger = Logger.getLogger(fileName);
		FileHandler fh;

		try {

			// This block configure the logger with handler and formatter
			fh = new FileHandler(fileName);
			logger.addHandler(fh);
			SimpleFormatter formatter = new SimpleFormatter();
			fh.setFormatter(formatter);
			logger.info("test");

		} catch (SecurityException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}

	}

	Logger logger;

	private Path path;

	private Double openingBalance;

	private Double currentBalance;

	private CurrencyPair currencyPair = new CurrencyPair("OMG", "BTC");

	private String currencyPairString = "BTC-OMG";

	private Double tradingPercentage = 70.0; // Trades will be this percentage of current balance

	private Double omiBuyScoreConfirm = 60.0; // As a percentage, buy order score needs to be stronger than sell orders
												// by
												// this
												// percentage in order to confirm a triggered buy order
	private Double omiSellScoreConfirm = 40.0; // As a percentage, sell orders needs to be stronger than buy orders by
												// this
												// percentage in order to confirm a triggered sell order

	private Double orderBookBandSize = 3.0; // Only prices within a percentage of this value on the order book get used

	private Integer orderBookCheckInterval = 300; // In seconds

	private Integer orderBookPeriods = 14; // Use the average of the last number of order book periods in calculation,
											// order
											// books are incredibly volatile and this is intended to smooth that out
											// somewhat.

	private Integer orderBookPollingInterval = 10; // In seconds, when checking order book how to long to wait before
													// each subsequent poll

	private Integer orderBookPollingChecks = 5; // Number of times to poll the orderbook with each check

	private Integer rsiNumberOfPeriods = 14; // Number of periods to use for RSI calculation

	private Integer rsiRecheckInterval = 5; // How often to recheck RSI - in minutes

	private BittrexChartDataPeriodType rsiPeriod = BittrexChartDataPeriodType.THIRTY_MIN; // Period interval used for
																							// RSI calculation
	private Integer rsiOverbought = 74;
	private Integer rsiOverboughtConfirm = 68;
	private Integer rsiOverboughtConfirmExpired = 60;

	private Integer rsiOversold = 26;
	private Integer rsiOversoldConfirm = 32;
	private Integer rsiOversoldConfirmExpired = 40;

	private Double rsiValue = null;

	private LinkedList<OrderbookScoreItem> recentBuyScores = new LinkedList<OrderbookScoreItem>();
	private LinkedList<OrderbookScoreItem> recentSellScores = new LinkedList<OrderbookScoreItem>();

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

					logger.info("An unexpected exception has occurred during RSI calculation, error:" + e.getMessage());
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

					logger.info("An unexpected exception has occurred during RSI calculation, error:" + e.getMessage());
					e.printStackTrace();
				}
			}

		};

		thread.start();

	}

	private OrderbookScore orderBookPoll() throws IOException {
		OrderbookScore result = new OrderbookScore();
		OrderBook orderbook = marketDataService.getOrderBook(currencyPair, 10);

		BigDecimal lastPrice = marketDataService.getBittrexTicker(currencyPairString).getLast();
		double bandPriceAdjustment = lastPrice.doubleValue() * (orderBookBandSize / 100);
		logger.info(("Last price:" + lastPrice));
		logger.info(("Band adjust:" + bandPriceAdjustment));

		double totalBuyPressureScore = calculateBuyPressureScore(orderbook, lastPrice, bandPriceAdjustment);
		double totalSellPressureScore = calculateSellPressureScore(orderbook, lastPrice, bandPriceAdjustment);

		logger.info(("totalBuyPressureScore:" + totalBuyPressureScore));
		logger.info(("totalSellPressureScore:" + totalSellPressureScore));
		result.setBuyPressureScore(new OrderbookScoreItem(totalBuyPressureScore, Calendar.getInstance()));
		result.setSellPressureScore(new OrderbookScoreItem(totalSellPressureScore, Calendar.getInstance()));

		return result;
	}

	private OrderbookScore getSmoothedOrderbookScore() throws IOException, InterruptedException {
		OrderbookScore result = new OrderbookScore();
		List<OrderbookScore> orderbookScores = new ArrayList<OrderbookScore>();

		for (int i = 0; i < orderBookPollingChecks; i++) {
			orderbookScores.add(orderBookPoll());
			TimeUnit.SECONDS.sleep(orderBookPollingInterval);
		}

		// Eliminate top buy pressure poll result and top sell pressure poll result
		// in an attempt to eliminate manipulated order books. This won't be perfect
		// but should be alot better than not doing it at all.

		List<OrderbookScore> medianOrderbookScores = obtainMedianScores(orderbookScores);

		double averageBuyScore = getAverageBuyScore(medianOrderbookScores);
		double averageSellScore = getAverageSellScore(medianOrderbookScores);

		result.setBuyPressureScore(new OrderbookScoreItem(averageBuyScore, Calendar.getInstance()));
		result.setSellPressureScore(new OrderbookScoreItem(averageSellScore, Calendar.getInstance()));
		
		return result;
	}

	private double getAverageSellScore(List<OrderbookScore> medianOrderbookScores) {
		double total = 0.0;
		for (OrderbookScore orderbookScore : medianOrderbookScores) {
			total += orderbookScore.getSellPressureScore().getScoreAmount();
		}
		return (total / medianOrderbookScores.size());
	}

	private double getAverageBuyScore(List<OrderbookScore> medianOrderbookScores) {
		double total = 0.0;
		for (OrderbookScore orderbookScore : medianOrderbookScores) {
			total += orderbookScore.getBuyPressureScore().getScoreAmount();
		}
		return (total / medianOrderbookScores.size());
	}

	private List<OrderbookScore> obtainMedianScores(List<OrderbookScore> orderbookScores) {
		List<OrderbookScore> result = new ArrayList<OrderbookScore>();
		double topBuyResult = 0.0;
		double topSellResult = 0.0;
		for (OrderbookScore orderbookScore : orderbookScores) {
			if (orderbookScore.getBuyPressureScore().getScoreAmount() > topBuyResult) {
				topBuyResult = orderbookScore.getBuyPressureScore().getScoreAmount();
			}
			if (orderbookScore.getSellPressureScore().getScoreAmount() > topSellResult) {
				topSellResult = orderbookScore.getSellPressureScore().getScoreAmount();
			}
		}
		for (OrderbookScore orderbookScore : orderbookScores) {
			if ((orderbookScore.getBuyPressureScore().getScoreAmount() < topBuyResult)
					&& (orderbookScore.getSellPressureScore().getScoreAmount() < topSellResult)) {
				result.add(orderbookScore);
			}
		}
		return result;
	}

	private void checkOrderBook() throws IOException, InterruptedException {

		if (recentBuyScores.size() == orderBookPeriods) {
			recentBuyScores.removeFirst();
		}
		if (recentSellScores.size() == orderBookPeriods) {
			recentSellScores.removeFirst();
		}

		OrderbookScore orderbookScore = getSmoothedOrderbookScore();

		recentBuyScores.add(
				new OrderbookScoreItem(orderbookScore.getBuyPressureScore().getScoreAmount(), Calendar.getInstance()));
		recentSellScores.add(
				new OrderbookScoreItem(orderbookScore.getSellPressureScore().getScoreAmount(), Calendar.getInstance()));

		if ((recentBuyScores.size() == orderBookPeriods) && (recentSellScores.size() == orderBookPeriods)) {
			double orderBookMomentumIndicator = calculateOrderBookMomentumIndicator(recentBuyScores, recentSellScores);
			double averagedBuyPressureScore = calculateAverageScore(recentBuyScores);
			double averagedSellPressureScore = calculateAverageScore(recentSellScores);
			logger.info(("averagedBuyPressureScore:" + averagedBuyPressureScore));
			logger.info(("averagedSellPressureScore:" + averagedSellPressureScore));

			if (signalStatus.equals(SignalStatus.CONFIRM_BUY)) {
				logger.info(("In confirm buy signal status, checking order book, orderBookMomentumIndicator:"
						+ orderBookMomentumIndicator + ", needs to be greater than omiBuyScoreConfirm of :"
						+ omiBuyScoreConfirm));
				if (orderBookMomentumIndicator > omiBuyScoreConfirm) {
					
					if (!openBuyOrder) {
						BigDecimal lastPrice = marketDataService.getBittrexTicker(currencyPairString).getLast();
						logger.info(("Executing buy order@price:" + lastPrice));
						openBuyOrder = true;
						openSellOrder = false;
						signalStatus = SignalStatus.NONE;
					}
				}
			}

			if (signalStatus.equals(SignalStatus.CONFIRM_SELL)) {
				logger.info(("In confirm sell signal status, checking order book, orderBookMomentumIndicator:"
						+ orderBookMomentumIndicator + ", needs to be less than omiSellScoreConfirm of :"
						+ omiSellScoreConfirm));

				if (orderBookMomentumIndicator < omiSellScoreConfirm) {
					if (!openSellOrder) {
						BigDecimal lastPrice = marketDataService.getBittrexTicker(currencyPairString).getLast();
						logger.info(("Executing sell order@price:" + lastPrice));
						openSellOrder = true;
						openBuyOrder = false;
						signalStatus = SignalStatus.NONE;
					}
				}
			}
		}

		TimeUnit.SECONDS.sleep(orderBookCheckInterval - (orderBookPollingInterval * orderBookPollingChecks));

		checkOrderBook();

	}

	private double calculateAverageScore(LinkedList<OrderbookScoreItem> recentScores) {

		double total = 0;
		Integer count = 0;

		for (OrderbookScoreItem scoreItem : recentScores) {
			count++;
			double percMod = count.doubleValue() / orderBookPeriods.doubleValue();
			double modifiedScore = (percMod * scoreItem.getScoreAmount());

			total += modifiedScore;
		}
		return total / recentScores.size();
	}

	private double calculateSellPressureScore(OrderBook orderbook, BigDecimal lastPrice, double bandPriceAdjustment)
			throws IOException {
		double total = 0;
		double maxPrice = lastPrice.doubleValue() + bandPriceAdjustment;
		logger.info(("maxPrice sellPressure:" + maxPrice));
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

	private double calculateBuyPressureScore(OrderBook orderbook, BigDecimal lastPrice, double bandPriceAdjustment)
			throws IOException {
		double total = 0;
		double minPrice = lastPrice.doubleValue() - bandPriceAdjustment;
		logger.info(("minPrice buyPressure:" + minPrice));
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

	private double calculateOrderBookMomentumIndicator(LinkedList<OrderbookScoreItem> recentBuyScores,
			LinkedList<OrderbookScoreItem> recentSellScores) {

		Double relativeStrength = getOrderbookMomentumScore(recentBuyScores, recentSellScores);

		double OMIScore = 100 - 100 / (1 + relativeStrength);
		logger.info("OMIScore:" + OMIScore);
		return OMIScore;
	}

	private Double getOrderbookMomentumScore(LinkedList<OrderbookScoreItem> recentBuyScores,
			LinkedList<OrderbookScoreItem> recentSellScores) {
		Double totalBuyWin = 0.00000001;
		Double totalSellWin = 0.00000001;

		for (int i = 0; i < (recentBuyScores.size() - 1); i++) {
			OrderbookScoreItem scoreBuyItem = recentBuyScores.get(i);
			OrderbookScoreItem scoreBuyNext = recentBuyScores.get(i + 1);
			OrderbookScoreItem scoreSellItem = recentSellScores.get(i);
			OrderbookScoreItem scoreSellNext = recentSellScores.get(i + 1);

			double buyMomentumPeriodScore = scoreBuyNext.getScoreAmount() - scoreBuyItem.getScoreAmount();
			double sellMomentumPeriodScore = scoreSellNext.getScoreAmount() - scoreSellItem.getScoreAmount();

			if (buyMomentumPeriodScore > sellMomentumPeriodScore) {
				totalBuyWin += buyMomentumPeriodScore - sellMomentumPeriodScore;
			} else {
				totalSellWin += sellMomentumPeriodScore - buyMomentumPeriodScore;
			}
		}
		return totalBuyWin / totalSellWin;

	}

	private Double getBuyPressureGain(LinkedList<OrderbookScoreItem> recentBuyScores2) {
		// TODO Auto-generated method stub
		return null;
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

	private void calculateRSI() throws IOException, InterruptedException { // See
																			// http://www.investopedia.com/terms/r/rsi.asp
		ArrayList<BittrexChartData> chartData = null;
		try {
			chartData = marketDataService.getBittrexChartData(currencyPair, rsiPeriod);
			List<BittrexChartData> chartDataTrimmed = chartData
					.subList(Math.max(chartData.size() - rsiNumberOfPeriods, 0), chartData.size());
			Double averageUpPeriodGain = getUpPeriodGain(chartDataTrimmed);
			Double averageDownPeriodLoss = getDownPeriodLoss(chartDataTrimmed);
			Double relativeStrength = averageUpPeriodGain / averageDownPeriodLoss;
			rsiValue = 100 - 100 / (1 + relativeStrength);
			logger.info(("** RSI value : [ " + rsiValue + " ] **"));
			applyRSITriggers();
			TimeUnit.MINUTES.sleep(rsiRecheckInterval);

		} catch (Exception e) {
			logger.info("An unexpected exception has occurred during the bittrex api call");
			e.printStackTrace();
			TimeUnit.MINUTES.sleep(rsiRecheckInterval);
		}

		calculateRSI();

	}

	private void applyRSITriggers() {
		if (rsiValue <= rsiOversold) {
			if (!openBuyOrder) {
				signalStatus = SignalStatus.TRIGGER_BUY;
			}
		}

		if (rsiValue >= rsiOverbought) {
			if (!openSellOrder) {
				signalStatus = SignalStatus.TRIGGER_SELL;
			}
		}

		if ((signalStatus.equals(SignalStatus.TRIGGER_BUY)) && (rsiValue >= rsiOversoldConfirm)) {
			if (!openBuyOrder) {
				signalStatus = SignalStatus.CONFIRM_BUY;
			}
		}

		if ((signalStatus.equals(SignalStatus.TRIGGER_SELL)) && (rsiValue <= rsiOverboughtConfirm)) {
			if (!openSellOrder) {
				signalStatus = SignalStatus.CONFIRM_SELL;
			}
		}

		if ((signalStatus.equals(SignalStatus.TRIGGER_BUY) || signalStatus.equals(SignalStatus.CONFIRM_BUY))
				&& (rsiValue >= rsiOversoldConfirmExpired)) {
			signalStatus = SignalStatus.NONE;
		}

		if ((signalStatus.equals(SignalStatus.TRIGGER_SELL) || signalStatus.equals(SignalStatus.CONFIRM_SELL))
				&& (rsiValue <= rsiOverboughtConfirmExpired)) {
			signalStatus = SignalStatus.NONE;
		}

		logger.info(("Signal Status:" + signalStatus));

	}

	private void initialise() {
		exchange = ExchangeFactory.INSTANCE.createExchange(BittrexExchange.class.getName());
		marketDataService = (BittrexMarketDataService) exchange.getMarketDataService();
		currentBalance = openingBalance;

	}

}
