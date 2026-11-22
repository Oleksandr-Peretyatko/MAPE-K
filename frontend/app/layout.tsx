import type { Metadata } from "next"
import { Inter, JetBrains_Mono } from "next/font/google"
import "./globals.css"
import { SnapshotProvider } from "@/contexts/SnapshotContext"
import { AppShell } from "@/components/layout/AppShell"
import { TooltipProvider } from "@/components/ui/tooltip"
import { ThemeProvider } from "@/components/layout/ThemeProvider"

const inter = Inter({ subsets: ["latin"], variable: "--font-inter" })
const jetbrainsMono = JetBrains_Mono({ subsets: ["latin"], variable: "--font-jetbrains-mono" })

export const metadata: Metadata = {
  title: "Refactor Tool — MAPE-K Dashboard",
  description: "Self-adaptive microservices architecture analysis dashboard using the MAPE-K autonomic loop.",
}

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" className={`${inter.variable} ${jetbrainsMono.variable}`} suppressHydrationWarning>
      <body className="bg-background">
        <ThemeProvider attribute="class" defaultTheme="dark" enableSystem={false}>
          <TooltipProvider delayDuration={300}>
            <SnapshotProvider>
              <AppShell>{children}</AppShell>
            </SnapshotProvider>
          </TooltipProvider>
        </ThemeProvider>
      </body>
    </html>
  )
}
