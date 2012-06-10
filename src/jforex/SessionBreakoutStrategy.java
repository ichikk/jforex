package jforex;

import java.math.BigDecimal;
import java.text.DateFormat;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Currency;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

import com.dukascopy.api.Configurable;
import com.dukascopy.api.IAccount;
import com.dukascopy.api.IBar;
import com.dukascopy.api.IConsole;
import com.dukascopy.api.IContext;
import com.dukascopy.api.IEngine;
import com.dukascopy.api.IHistory;
import com.dukascopy.api.IIndicators;
import com.dukascopy.api.IMessage;
import com.dukascopy.api.IOrder;
import com.dukascopy.api.IStrategy;
import com.dukascopy.api.ITick;
import com.dukascopy.api.IUserInterface;
import com.dukascopy.api.Instrument;
import com.dukascopy.api.JFException;
import com.dukascopy.api.OfferSide;
import com.dukascopy.api.Period;

public class SessionBreakoutStrategy implements IStrategy {
    private IEngine engine;
    private IConsole console;
    private IHistory history;
    private IContext context;
    private IIndicators indicators;
    private IUserInterface userInterface;
    private IAccount account;

    @Configurable("Instrument of London/US Session")
    public Instrument _instrumentLondonUS = Instrument.EURUSD;

    @Configurable("Take Profit of London/US Session")
    public double _tpLondonUS = 30;

    @Configurable("Instrument of Asian Session")
    public Instrument _instrumentAsian = Instrument.EURUSD;

    @Configurable("Take Profit of Asian Session")
    public double _tpAsian = 30;

    @Configurable("Time Period")
    public Period _period = Period.ONE_HOUR;

    @Configurable("Pips of Breakout")
    public double _bp = 10;

    @Configurable("Ratio of Credit")
    public double _marginRatio = 0.3;

    @Configurable("Limit of Lots")
    public double _limitLots = 6;

    @Configurable("Order Label")
    public String _label = "SBS";

//    private final Locale  BASE_LOCALE = Locale.US;
    private final Locale  BASE_LOCALE = Locale.JAPAN;
    private final Currency BASE_CURRENCY = Currency.getInstance(BASE_LOCALE);

    private double totalPips = 0;
    private double totalProfitLoss = 0;

    private class BreakoutOrder{
        private IEngine.OrderCommand command;
        private double limit;
        private double stop;
        BreakoutOrder(IEngine.OrderCommand command, double limit, double stop){
            this.command = command;
            this.limit = limit;
            this.stop = stop;

        }
    }

    public void onStart(IContext context) throws JFException {
        this.engine = context.getEngine();
        this.console = context.getConsole();
        this.history = context.getHistory();
        this.context = context;
        this.indicators = context.getIndicators();
        this.account = context.getAccount();
        this.userInterface = context.getUserInterface();

        context.setSubscribedInstruments(getSubscribedInstruments(new Instrument[]{_instrumentLondonUS, _instrumentAsian}));
    }

    public void onAccount(IAccount account) throws JFException {
        this.account = account;
    }

    public void onMessage(IMessage message) throws JFException {
        IOrder order = message.getOrder();
        switch (message.getType()) {
        case ORDER_FILL_OK :
            if (order.getLabel().startsWith(_label)) {
                print("Order " + order.getId() + "-" + order.getLabel() + "("+ order.getInstrument() +"): FILL " + order.getOrderCommand() + ": OPEN=" + order.getOpenPrice() + " TP=" + order.getTakeProfitPrice() + " SL=" + order.getStopLossPrice() + " VOL=" + (order.getAmount()*1000000));
            }
            break;
        case ORDER_CLOSE_OK :
            if (order.getLabel().startsWith(_label)) {
                print("Order " + order.getId() + "-" + order.getLabel() + "("+ order.getInstrument() +"): CLOSE --> [" + order.getProfitLossInPips() + "] pips/[" + order.getProfitLossInAccountCurrency() + "] "+ context.getAccount().getCurrency() );
                totalPips += order.getProfitLossInPips();
                totalProfitLoss += order.getProfitLossInAccountCurrency();
            }
            break;
        default :
            break;
        }
    }

    public void onStop() throws JFException {
        print("Total Pips --> " + totalPips + ", Total ProfitLoss -->" + totalProfitLoss);
    }

    public void onTick(Instrument instrument, ITick tick) throws JFException {
    }

    public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException {
        if(instrument != _instrumentLondonUS && instrument != _instrumentAsian) return;
        if(period != _period) return;
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
        cal.setTimeInMillis(bidBar.getTime());
        int week = cal.get(Calendar.DAY_OF_WEEK);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        if((week == Calendar.SUNDAY && hour < 23) || week == Calendar.SATURDAY) return;
        BreakoutOrder breakoutOrder = null;
        if(9 <= hour && hour <= 22 && instrument == _instrumentLondonUS){ //London Session
            cal.set(Calendar.HOUR_OF_DAY, 0);
            long from = history.getBarStart(period, cal.getTimeInMillis());
            cal.set(Calendar.HOUR_OF_DAY, 8);
            long to = history.getBarStart(period, cal.getTimeInMillis());
            breakoutOrder = getBreakoutOrder(instrument, period, askBar, bidBar, _tpLondonUS, from, to);
        }else if(23 <= hour || hour <= 8 && instrument == _instrumentAsian){ // Asian Session
            cal.add(Calendar.DAY_OF_MONTH, (week == Calendar.SUNDAY ) ? -2 : ((week == Calendar.MONDAY ) ? -3 : -1));
            cal.set(Calendar.HOUR_OF_DAY, 13);
            long from = history.getBarStart(period, cal.getTimeInMillis());
            cal.add(Calendar.DAY_OF_MONTH, (week == Calendar.SUNDAY ) ? 1 : ((week == Calendar.MONDAY ) ? 2 : 0));
            cal.set(Calendar.HOUR_OF_DAY, 22);
            long to = history.getBarStart(period, cal.getTimeInMillis());
            breakoutOrder = getBreakoutOrder(instrument, period, askBar, bidBar, _tpAsian, from, to);
        }
        if(breakoutOrder == null) return;
        for (IOrder order : engine.getOrders()) {
            if(order.getLabel().startsWith(_label) && order.getInstrument() == instrument) return;
        }
        engine.submitOrder(
                getLabel(bidBar.getTime()),
                instrument,
                breakoutOrder.command,
                getLots(instrument),
                0,
                0.5,
                breakoutOrder.stop,
                breakoutOrder.limit);
    }

    private BreakoutOrder getBreakoutOrder(Instrument instrument, Period period, IBar askBar, IBar bidBar, double tp, long from, long to) throws JFException{
        List<IBar> bars = history.getBars(instrument, period, OfferSide.BID, from, to);
        double high = Double.MIN_VALUE;
        double low = Double.MAX_VALUE;
        for (IBar bar : bars) {
            high = Math.max(high, bar.getHigh());
            low = Math.min(low, bar.getLow());
        }
        double open = bidBar.getOpen();
        double close = bidBar.getClose();
        double breakHigh = high + instrument.getPipValue() * _bp;
        double breakLow = low - instrument.getPipValue() * _bp;
        if(open < breakHigh && breakHigh < close){
            return new BreakoutOrder(IEngine.OrderCommand.BUY, tp==0?0:new BigDecimal(close + tp * instrument.getPipValue()).setScale(instrument.getPipScale(), BigDecimal.ROUND_DOWN).doubleValue(), low);
        }else if(open > breakLow && breakLow > close){
            return new BreakoutOrder(IEngine.OrderCommand.SELL, tp==0?0:new BigDecimal(close - tp * instrument.getPipValue()).setScale(instrument.getPipScale(), BigDecimal.ROUND_DOWN).doubleValue(), high);
        }else{
            return null;
        }
    }

    private double getLots(Instrument instrument) throws JFException
    {
        double lots;
        NumberFormat nf = NumberFormat.getCurrencyInstance(BASE_LOCALE);
        nf.setMaximumFractionDigits(2);

        double credit = account.getCreditLine();
        double equity = account.getEquity();
        if(!BASE_CURRENCY.equals(account.getCurrency())){
            double bcBid = history.getLastTick(Instrument.fromString(account.getCurrency()+"/"+BASE_CURRENCY)).getBid();
            credit *= bcBid;
            equity *= bcBid;
        }
        double latestBid = history.getLastTick(Instrument.fromString(instrument.getPrimaryCurrency()+"/"+BASE_CURRENCY)).getBid();
        double limit = credit * _marginRatio;
        lots = (new BigDecimal(limit/latestBid/(100*10000))).setScale(4, BigDecimal.ROUND_DOWN).doubleValue();
        lots = lots > _limitLots ? _limitLots : lots;
        print("equity="+nf.format(equity)+
              "/credit="+nf.format(credit)+
              "/bid="+nf.format(latestBid)+"("+instrument.getPrimaryCurrency()+")"+
              "/limit="+nf.format(limit)+
              "/lots="+lots);
        return lots;
    }

    private String getLabel(long time){
        DateFormat df = new SimpleDateFormat("yyyyMMdd_HHmm");
        df.setTimeZone(TimeZone.getTimeZone("GMT"));
        return _label+"_"+df.format(new Date(time));
    }

    private Set<Instrument> getSubscribedInstruments(Instrument[] instruments){
        Set<Instrument> subscribedInstruments = new HashSet<Instrument>();
        for (Instrument instrument : instruments) {
        	subscribedInstruments.add(instrument);
            if(!BASE_CURRENCY.equals(instrument.getPrimaryCurrency())){
            	subscribedInstruments.add(Instrument.fromString(instrument.getPrimaryCurrency()+"/"+BASE_CURRENCY));
            }
		}
        if(!BASE_CURRENCY.equals(account.getCurrency())){
            subscribedInstruments.add(Instrument.fromString(account.getCurrency()+"/"+BASE_CURRENCY));
        }
        return subscribedInstruments;
    }

    private void print(String msg){
        console.getOut().println(msg);
    }
}
