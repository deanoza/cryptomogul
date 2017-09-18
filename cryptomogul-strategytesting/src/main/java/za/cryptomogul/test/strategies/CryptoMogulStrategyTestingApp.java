package za.cryptomogul.test.strategies;

import java.io.IOException;

import org.knowm.xchange.currency.CurrencyPair;

import za.cryptomogul.test.strategies.highfrequency.OrderbookRSIStrategy;

public class CryptoMogulStrategyTestingApp {
	public static void main(String[] args) {

		OrderbookRSIStrategy orderBookRSIBotOMGBTC;
		try {
			orderBookRSIBotOMGBTC = new OrderbookRSIStrategy(new CurrencyPair("OMG", "BTC"), "BTC-OMG");

			OrderbookRSIStrategy orderBookRSIBotNEOBTC = new OrderbookRSIStrategy(new CurrencyPair("NEO", "BTC"),
					"BTC-NEO");
			OrderbookRSIStrategy orderBookRSIBotETHBTC = new OrderbookRSIStrategy(new CurrencyPair("ETH", "BTC"),
					"BTC-ETH");
			orderBookRSIBotOMGBTC.startBot();
			orderBookRSIBotNEOBTC.startBot();
			orderBookRSIBotETHBTC.startBot();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		System.out.println(OrderbookRSIStrategy.class.getName() + "Bot started!");

	}
}
