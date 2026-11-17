package com.gaeul.allin;

import com.gaeul.allin.json.*;
import java.util.*;

/** Independent examples exercise distribution, expiry amounts, stale/unknown data and quota units. */
public final class AccountRoutingPolicyOfflineTest {
    private static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static AccountRoutingPolicy.Seat seat(String id){return new AccountRoutingPolicy.Seat("provider/"+id,"fingerprint-"+id);}
    static JSONObject allowance(double total,double left,long reset){
        JSONObject w=new JSONObject().put("name","Credits").put("total",total).put("remaining",left).put("remainingPercent",left/total*100);
        if(reset>0)w.put("resetAt",reset);
        return new JSONObject().put("unit","credits").put("windows",new JSONArray().put(w));
    }
    static UsageLedger.Row row(int tokens){UsageLedger.Row r=new UsageLedger.Row();r.status=200;r.usageSource="known";r.in=tokens;r.out=0;return r;}
    public static void main(String[] args)throws Exception {
        long now=1800000000000L;
        AccountRoutingPolicy p=new AccountRoutingPolicy(()->now);
        List<AccountRoutingPolicy.Seat> seats=Arrays.asList(seat("a"),seat("b"),seat("c"),seat("d"));
        List<RecentUsageScheduler.Lease> leases=new ArrayList<>();Set<String> selected=new HashSet<>();
        for(int i=0;i<4;i++){RecentUsageScheduler.Lease l=p.select("usage",seats,"m",now);leases.add(l);selected.add(l.target);}
        check(selected.size()==4,"four in-flight requests spread over all four accounts");
        for(RecentUsageScheduler.Lease l:leases)l.cancel();
        RecentUsageScheduler.Lease large=p.select("usage",seats,"m",now);large.complete(row(10000));
        check(!p.select("usage",seats,"m",now).target.equals(large.target),"a large completed request weighs more than one request");
        AccountRoutingPolicy quota=new AccountRoutingPolicy(()->now);
        for(int i=0;i<seats.size();i++)quota.observe(seats.get(i),allowance(100,10+i*20,0),now);
        RecentUsageScheduler.Lease best=quota.select("usage",seats,"m",now);check(best.target.equals(seats.get(3).ref),"least consumed proportion goes first");best.cancel();
        check(quota.basis(seats,"m",now,"usage").equals("allowance"),"fresh complete quota coverage reported");
        check(quota.basis(seats,"m",now+AccountRoutingPolicy.FRESH_MS+1,"usage").equals("recent_tokens"),"stale quotas are not used");
        List<AccountRoutingPolicy.Seat> changed=Arrays.asList(new AccountRoutingPolicy.Seat(seats.get(0).ref,"replaced"),seats.get(1));
        check(quota.basis(changed,"m",now,"usage").equals("recent_tokens"),"changed credentials invalidate old quota");
        quota.observe(seats.get(0),null,now);check(quota.basis(seats,"m",now,"usage").equals("recent_tokens"),"unknown isn't zero; mixed data uses one comparable fallback");
        JSONObject urgent=allowance(100,80,now+30*86400000L);urgent.getJSONArray("windows").getJSONObject(0).put("runsOutAt",now+3600000L).put("runsOut",20);
        JSONObject later=allowance(100,95,now+30*86400000L);later.getJSONArray("windows").getJSONObject(0).put("runsOutAt",now+24*3600000L).put("runsOut",60);
        check(AccountRoutingPolicy.pressure(urgent,"m",now)>AccountRoutingPolicy.pressure(later,"m",now),"actual amount expiring per hour ranks urgent gift first");
        AccountRoutingPolicy exp=new AccountRoutingPolicy(()->now);exp.observe(seats.get(0),urgent,now);exp.observe(seats.get(1),later,now);
        RecentUsageScheduler.Lease early=exp.select("pace",seats.subList(0,2),"m",now);check(early.target.equals(seats.get(0).ref),"expiry choice uses expiry data");early.cancel();
        JSONObject invalid=allowance(100,80,0);invalid.put("expires",now+1000);check(AccountRoutingPolicy.pressure(invalid,"m",now)<0,"credential expiry is not credit expiry");
        invalid.getJSONArray("windows").getJSONObject(0).put("runsOutAt",now-1).put("runsOut",80);check(AccountRoutingPolicy.pressure(invalid,"m",now)<0,"expired gifts do not rank");
        JSONObject aside=allowance(100,0,now+1000);aside.getJSONArray("windows").getJSONObject(0).put("aside",true);check(AccountRoutingPolicy.remainingPercent(aside,"m",now)==null,"overlapping informational windows don't exhaust an account");
        check(AccountRoutingPolicy.time("2026-11-17 12:00:00")==AccountRoutingPolicy.time("2026-11-17T04:00:00Z"),"vendor UTC+8 timestamps parsed correctly");
        check(AccountRoutingPolicy.time("2026-02-31T04:00:00Z")==0,"invalid dates rejected");
        JSONObject sum=new JSONObject().put("Packages",new JSONArray().put(new JSONObject().put("PackageCode","gift")));
        JSONArray packs=new JSONArray().put(new JSONObject().put("PackageCode","gift").put("CycleCapacityRemainPrecise",20).put("DeductionEndTime",now+3600000));
        JSONObject got=WorkBuddyAllowance.withExpiry(allowance(100,80,0),sum,packs,now);
        check(got.getJSONArray("windows").getJSONObject(0).getDouble("runsOut")==20,"WorkBuddy only the expiring amount, not full 80 credits");
        packs.getJSONObject(0).put("CapacityType",4);got=WorkBuddyAllowance.withExpiry(allowance(100,80,0),sum,packs,now);
        check(!got.getJSONArray("windows").getJSONObject(0).has("runsOutAt"),"daily slices cannot impersonate expiring rewards");
        JSONObject enterprise=WorkBuddyAllowance.enterprise(new JSONObject().put("limitNum",100).put("credit",20).put("cycleResetTime","2026-12-01 00:00:00"));
        check(enterprise.getJSONArray("windows").getJSONObject(0).has("resetAt"),"enterprise cycle uses vendor date");
        check(AccountRoutingPolicy.valid("rotate")&&AccountRoutingPolicy.valid("usage")&&AccountRoutingPolicy.valid("pace")&&!AccountRoutingPolicy.valid("arbitrary"),"settings whitelist");
        System.out.println("AccountRoutingPolicyOfflineTest PASS: "+checks+" checks");
    }
}
