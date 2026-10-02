import { useEffect, useState } from "react";
import type { Label } from "./api";

export const LABELS: Label[] = ["normal", "card_testing", "money_mule", "account_takeover"];
export const FRAUD_LABELS: Label[] = ["card_testing", "money_mule", "account_takeover"];

export const LABEL_NAMES: Record<Label, string> = {
  normal: "Everyday",
  card_testing: "Card testing",
  money_mule: "Money mule",
  account_takeover: "Account takeover",
};

export const labelVar = (label: Label) => `var(--label-${label.replaceAll("_", "-")})`;

export interface Palette {
  labels: Record<Label, string>;
  surface: string;
  ink: string;
  inkSecondary: string;
  inkMuted: string;
  grid: string;
}

function readPalette(): Palette {
  const style = getComputedStyle(document.documentElement);
  const read = (name: string) => style.getPropertyValue(name).trim();
  return {
    labels: Object.fromEntries(LABELS.map((l) => [l, read(`--label-${l.replaceAll("_", "-")}`)])) as Record<
      Label,
      string
    >,
    surface: read("--surface"),
    ink: read("--ink"),
    inkSecondary: read("--ink-secondary"),
    inkMuted: read("--ink-muted"),
    grid: read("--grid"),
  };
}

/** The CSS colour tokens, re-read when the system switches between light and dark. */
export function usePalette(): Palette {
  const [palette, setPalette] = useState(readPalette);
  useEffect(() => {
    const query = matchMedia("(prefers-color-scheme: dark)");
    const update = () => setPalette(readPalette());
    query.addEventListener("change", update);
    return () => query.removeEventListener("change", update);
  }, []);
  return palette;
}

export function useReducedMotion(): boolean {
  const [reduced, setReduced] = useState(() => matchMedia("(prefers-reduced-motion: reduce)").matches);
  useEffect(() => {
    const query = matchMedia("(prefers-reduced-motion: reduce)");
    const update = () => setReduced(query.matches);
    query.addEventListener("change", update);
    return () => query.removeEventListener("change", update);
  }, []);
  return reduced;
}

const pounds = new Intl.NumberFormat("en-GB", { style: "currency", currency: "GBP" });
const compactPounds = new Intl.NumberFormat("en-GB", {
  style: "currency",
  currency: "GBP",
  notation: "compact",
  maximumFractionDigits: 1,
});

export const formatMoney = (minor: number) => pounds.format(minor / 100);
export const formatMoneyCompact = (minor: number) =>
  minor >= 1_000_000 ? compactPounds.format(minor / 100) : pounds.format(Math.round(minor / 100));
export const formatTime = (iso: string) =>
  new Date(iso).toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit", second: "2-digit" });
