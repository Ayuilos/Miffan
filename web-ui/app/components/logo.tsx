import type { ComponentPropsWithRef } from "react";

type LogoProps = Omit<ComponentPropsWithRef<"img">, "src">;

export default function Logo({ alt = "Miffan", ...props }: LogoProps) {
  return <img src="/miffan-icon.svg" alt={alt} {...props} />;
}
