// IGNORE_K2
public class C {
    public static void main(String[] args) {
        int a = switch (args.length) {
            case 1: {
                int a = 1;
                System.out.print("1");
            }

            case 2: {
                int a = 2;
                System.out.print("2");
            }
        }
    }
}