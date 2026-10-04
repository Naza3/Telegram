package org.telegram.messenger.ai;
public final class AiSummarySettings {
 public static final class Config {public int inputCharacterBudget=64000,maxOutputTokens=512;public String model="synthetic";public boolean valid=true;}
 public static String validate(Config c){return c.valid?null:"Synthetic invalid config";}
}
