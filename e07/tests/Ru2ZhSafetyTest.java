package com.stand.bridge;
import java.nio.file.*;
import java.util.Objects;
public final class Ru2ZhSafetyTest {
 static int count;
 static void check(String s, String expected) {
  String got=Ru2Zh.ru2zh(s);
  count++;
  if (!Objects.equals(got, expected)) throw new AssertionError(s+" -> "+got+", expected "+expected);
 }
 public static void main(String[] args) throws Exception {
  if (Ru2Zh.ALLOW_UNSAFE || !Ru2Zh.ALLOW_TRUNK) throw new AssertionError("Expected trunk-only release profile");
  for (boolean gate : new boolean[]{false,true}) {
   Ru2Zh.ALLOW_UNSAFE=gate;
   // Every baseline utterance with an added standalone negation must stay inactive,
   // including the early vehicleInfo and current-media exceptions.
   for (String line: Files.readAllLines(Path.of(args[0]))) {
    if (line.isBlank() || line.startsWith("#")) continue;
    check("не "+line.split("\t")[0], null);
   }
   for (String s: new String[]{"не поднимай окно","не опускай окно","не грей сиденье",
      "НЕ грей сиденье", "не, грей сиденье", "не-показывай пробег", "покажи не пробег, а заряд",
      "не включай режим не беспокоить", "не открывай багажник", "не надо"}) check(s,null);
   // Off requests that previously chose a positive mode/number branch are rejected.
   for (String s: new String[]{"выключи климат авто","выключи кондиционер на 22",
      "выключи подогрев сиденья на два","выключи массаж на два",
      "выключи вентиляцию сиденья на три","выключи подогрев руля на два",
      "выключи холодильник на пять градусов","выключи яркость экрана на максимум",
      "выключи подсветку красным","выключи дворники на два","выключи автопарковку",
      "останови автопарковку","закрой окно на 30 процентов","закрой люк наполовину",
      "выключи радио на 101 fm","выключи подножку длиннее","выключи подогрев сиденья сильнее",
      "выключи режим спорт","выключи руль выше","закрой настройки","выключи шрифт побольше"}) check(s,null);
   for (String input : new String[]{"открой шторки дома", "открой окно в квартире", "включи домашний кондиционер",
      "открой окно на 0 процентов", "открой окно на 150 процентов", "открой люк на минус 20 процентов",
      "включи климат и открой окно", "открой окно и включи климат", "включи обогрев руля и сиденья",
      "включи свет и дуй в лицо и в ноги", "ответь на вопрос", "сбрось настройки", "сбрось музыку",
      "подогрев сиденья на пять", "дворники на пять", "обдув на девять", "вентиляция сиденья на четыре",
      "массаж на ноль", "подогрев руля на пять", "подогрев на пять", "вентиляция сиденья на минус два"}) {
    check(input, null);
    if (!Ru2Zh.isGuarded(input)) throw new AssertionError("fuzzy rescue must be guarded: "+input);
   }
   for (String input : new String[]{"круиз на 300", "круиз на минус 60", "круиз на ноль",
      "дистанция пять", "дистанция минус два", "сохрани сиденье номер два", "профиль сиденья первый"}) {
    check(input,null);
    if (!Ru2Zh.isGuarded(input)) throw new AssertionError("unsupported parameter must block fuzzy rescue: "+input);
   }
   check("обдув на восемь", "风量调到8档");
   check("дуй в лицо и в ноги", "空调吹面吹脚");
   check("обдув на ноги и тело", "空调吹面吹脚");
   check("температура двадцать три и пять десятых", "把温度调到23.5度");
   check("открой окно на 30 процентов", "主驾车窗开到百分之30");
   check("подогрев сиденья на три", "主驾座椅加热3档");
   check("ответь", "接听");
   check("прими звонок", "接听");
   check("сбрось звонок", "挂断");
   // Preserve unambiguous off, movement, automatic-close features and «мне».
   check("мне холодно","温度调高一点");
   check("мне жарко","温度调低一点");
   check("выключи климат","关闭空调");
   check("выключи кондиционер","关闭制冷模式");
   check("выключи подогрев сиденья","关闭主驾座椅加热");
   check("выключи массаж","关闭主驾座椅按摩");
   check("закрой окно","关闭主驾车窗");
   check("опусти окно","打开主驾车窗");
   check("погаси экран","息屏");
   check("выключи рециркуляцию","打开外循环");
   check("закрывай окна в дождь","打开雨天自动关窗");
   check("закрой окна на охране","打开锁车关窗");
   check("закрывать при уходе","打开离车自动闭锁");
   check("выключи закрытие окон в дождь","关闭雨天自动关窗");
   check("нет","不用了");
   check("отмена","不用了");
  }
  Ru2Zh.ALLOW_UNSAFE=false;
  Ru2Zh.ALLOW_TRUNK=false;
  check("открой дверь",null);
  check("открой капот",null);
  check("включи автопилот",null);
  check("пауза парковки",null);
  System.out.println("SAFETY_PASS="+count+" FAIL=0");
 }
}
