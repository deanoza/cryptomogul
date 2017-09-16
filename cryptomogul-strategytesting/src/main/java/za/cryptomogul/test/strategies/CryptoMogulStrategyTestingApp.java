package za.cryptomogul.test.strategies;

import za.cryptomogul.test.strategies.highfrequency.OrderbookRSIStrategy;

/**
 * Hello world!
 *
 */
public class CryptoMogulStrategyTestingApp 
{
    public static void main( String[] args )
    {
    	OrderbookRSIStrategy orderBookRSIBot = new OrderbookRSIStrategy();
    	orderBookRSIBot.startBot();
    	System.out.println(OrderbookRSIStrategy.class.getName()+"Bot started!");
    	
    }
}
