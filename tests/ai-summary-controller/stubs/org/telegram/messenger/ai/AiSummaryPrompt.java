package org.telegram.messenger.ai;
public final class AiSummaryPrompt {public static int dataBudget(PromptOptions p,int input,int output){if(input<=output)throw new IllegalArgumentException("Synthetic invalid budget");return input-output;}}
