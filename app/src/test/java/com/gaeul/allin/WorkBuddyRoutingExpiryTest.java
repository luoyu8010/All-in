package com.gaeul.allin;
import com.gaeul.allin.json.*;
import java.util.*;
import java.io.*;

/** Fixed plugin-derived package codes and independent HTTP-envelope fixtures; no vendor network. */
public final class WorkBuddyRoutingExpiryTest {
    static int checks;
    static void check(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
    public static void main(String[] args)throws Exception {
        long now=System.currentTimeMillis(),soon=now+3600000;
        String code="TCACA_code_007_nzdH5h4Nl0";
        JSONObject sum=WorkBuddyAllowanceRegressionTest.sum(WorkBuddyAllowanceRegressionTest.pkg(100,20,80).put("PackageCode","base"));
        JSONObject detail=new JSONObject().put("PackageCode",code).put("CycleCapacityRemainPrecise",25).put("CycleCapacitySizePrecise",30).put("DeductionEndTime",soon);
        JSONObject got=WorkBuddyAllowance.withExpiry(WorkBuddyAllowance.summary(sum),sum,new JSONArray().put(detail),now);
        check(got.getDouble("remaining")==105,"unlisted activity rewards counted once");
        JSONObject window=got.getJSONArray("windows").getJSONObject(0);check(window.getDouble("total")==130&&window.getDouble("runsOut")==25,"actual expiring portion retained");
        sum.getJSONArray("Packages").put(WorkBuddyAllowanceRegressionTest.pkg(30,5,25).put("PackageCode",code));
        got=WorkBuddyAllowance.withExpiry(WorkBuddyAllowance.summary(sum),sum,new JSONArray().put(detail),now);
        check(got.getDouble("remaining")==105,"summary/detail reward not counted twice");
        List<WorkBuddyAllowanceRegressionTest.Conn> sent=new ArrayList<>();
        final JSONObject summary=sum;
        got=WorkBuddyAllowance.fetch("workbuddy",WorkBuddyAllowanceRegressionTest.account("routing"),url->{
            try {
                JSONObject data=url.endsWith("get-user-resource-summary")?summary:new JSONObject().put("TotalCount",url.endsWith("paid-packages")?1:0).put("Accounts",url.endsWith("paid-packages")?new JSONArray().put(detail):new JSONArray());
                WorkBuddyAllowanceRegressionTest.Conn c=new WorkBuddyAllowanceRegressionTest.Conn(url,data);sent.add(c);return c;
            }catch(Exception e){throw new IOException(e);}
        },true);
        check(sent.size()==3,"summary, paid and free lists read, no inference/check-in");
        check(sent.stream().allMatch(c->c.closed),"all meter connections closed");
        JSONObject paid=new JSONObject(sent.get(1).sent.toString("UTF-8")),free=new JSONObject(sent.get(2).sent.toString("UTF-8"));
        check(paid.getInt("PageNumber")==1&&paid.getInt("PageSize")==200&&paid.getBoolean("NeedRenewInfo"),"fixed plugin pagination shape");
        check(free.getString("SlicePeriodStartTime").endsWith("00:00:00")&&free.getString("SlicePeriodEndTime").endsWith("23:59:59"),"UTC+8 daily slice bounds");
        check(got.getJSONArray("windows").getJSONObject(0).getDouble("runsOut")==25,"real fetch keeps expiry metadata");
        check(!got.toString().contains("fixture-")&&!got.toString().contains("refresh-"),"no credentials in allowance");
        JSONObject fallback=WorkBuddyAllowance.fetch("workbuddy",WorkBuddyAllowanceRegressionTest.account("routing"),url->{try{WorkBuddyAllowanceRegressionTest.Conn c=new WorkBuddyAllowanceRegressionTest.Conn(url,summary);if(!url.endsWith("get-user-resource-summary"))c.status=503;return c;}catch(Exception e){throw new IOException(e);}},true);
        check(fallback.getDouble("remaining")==105&&!fallback.optBoolean("expiryKnown",true),"detail failure preserves known balance and marks expiry unknown");
        System.out.println("WorkBuddyRoutingExpiryTest PASS: "+checks+" checks");
    }
}
